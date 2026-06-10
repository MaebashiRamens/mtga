package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.FeatureOverride
import com.example.mtga.common.PremiumMode
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetResolver
import com.example.mtga.config.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Force client-side Truth+ feature gates.
 *
 * Each premium feature has a 3-state mode:
 *  - Default:     stock behaviour (button visible, click → upsell)
 *  - ForceEnable: button visible, click bypasses upsell (server may reject)
 *  - Hide:        button hidden in the post composer
 *
 * Hide patches the Features fields to false so the compose-time
 * `if (Features.editsVisible) renderEditButton()` decides to render nothing.
 * ForceEnable patches them to true, plus the L6.U helpers (which AND
 * Features with a US-country geofence).
 *
 * Client-side only. The server may still reject the actual schedule/edit
 * operation if the account has no Truth+ subscription.
 */
class FeatureFlagHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "FeatureFlag"

    override fun hook(classLoader: ClassLoader) {
        val tv = Settings.isOn(SettingKeys.EnableTv)
        val edit = Settings.premiumModeOf(SettingKeys.PostEditMode)
        val schedule = Settings.premiumModeOf(SettingKeys.PostScheduleMode)
        // Per-field overrides: non-Default means we have work to do even
        // when all high-level toggles are off.
        val anyPerFieldOverride =
            Settings.featureOverrides.any { Settings.featureOverrideOf(it.key) != FeatureOverride.Default }
        if (!tv && edit == PremiumMode.Default && schedule == PremiumMode.Default && !anyPerFieldOverride) {
            XposedBridge.log("[$TAG] No feature flags requested; skipping")
            return
        }

        patchFeaturesConstructor(classLoader, tv, edit, schedule)
        if (edit == PremiumMode.ForceEnable || schedule == PremiumMode.ForceEnable) {
            patchPremiumGate(classLoader, edit, schedule)
        }
    }

    /**
     * Features ctor declaration order in v1.27.0:
     *   0: tvEnabled                       Z
     *   1: forYouEnabled                   Z
     *   2: editsEnabled                    LBoolean;
     *   3: editsVisible                    LBoolean;
     *   4: scheduleEnabled                 LBoolean;
     *   5: scheduleVisible                 LBoolean;
     *   6: gemsEnabled                     LBoolean;
     *   7: gemsVisible                     LBoolean;
     *   8: predictionsEnabled              LBoolean;        (v1.26.2+)
     *   9: videoScrollingEnabled           LBoolean;        (v1.26.2+)
     *  10: liveContentCarouselEnabled      LBoolean;        (v1.27.0+)
     *
     * Older v1.24.x / v1.26.1 ctors have only 8 args (no predictions /
     * videoScrolling / liveContentCarousel); v1.26.2 has 10. We range-check
     * each write so per-field overrides for absent slots silently no-op.
     *
     * Precedence — high-level toggles run first, per-field overrides last:
     *  1) `enable_tv` toggle  →  args[0] = true
     *  2) `post_edit_mode`    →  args[2,3]
     *  3) `post_schedule_mode`→  args[4,5]
     *  4) Settings.featureOverrides — Force(True|False) writes args[index]
     *     for every entry whose user choice isn't Default.
     */
    private fun patchFeaturesConstructor(
        classLoader: ClassLoader,
        tv: Boolean,
        edit: PremiumMode,
        schedule: PremiumMode,
    ) {
        val featuresClass = XposedHelpers.findClass(resolver.resolveFeaturesClass(), classLoader)
        // Snapshot the override map once at install time. Settings cache is
        // read-only for the lifetime of the host process.
        val overrideSnapshot: List<Triple<Int, Boolean, Any>> =
            Settings.featureOverrides.mapNotNull { entry ->
                val choice = Settings.featureOverrideOf(entry.key)
                if (choice == FeatureOverride.Default) {
                    null
                } else {
                    val forcedValue: Any =
                        if (entry.isNullableBoolean) {
                            if (choice == FeatureOverride.ForceTrue) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
                        } else {
                            if (choice == FeatureOverride.ForceTrue) true else false
                        }
                    Triple(entry.featureIndex, entry.isNullableBoolean, forcedValue)
                }
            }

        XposedBridge.hookAllConstructors(
            featuresClass,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args
                    // Older builds expose synthetic shorter overloads (e.g.
                    // the all-defaults helper ctor); the v1.24.x main ctor
                    // has 8 args. Skip writes for indices past the actual
                    // arg count so we degrade gracefully.
                    if (args.size < 8) return
                    // High-level toggles first.
                    if (tv && args.size > 0) args[0] = true
                    applyMode(edit, args, enabledIndex = 2, visibleIndex = 3)
                    applyMode(schedule, args, enabledIndex = 4, visibleIndex = 5)
                    // Per-field overrides last; they win over the high-level
                    // toggles when both touch the same slot.
                    for ((index, _, value) in overrideSnapshot) {
                        if (index in args.indices) {
                            args[index] = value
                        }
                    }
                }
            },
        )
        XposedBridge.log(
            "[$TAG] Features constructor overrides installed " +
                "(tv=$tv, edit=$edit, schedule=$schedule, perField=${overrideSnapshot.size})",
        )
    }

    private fun applyMode(
        mode: PremiumMode,
        args: Array<Any?>,
        enabledIndex: Int,
        visibleIndex: Int,
    ) {
        when (mode) {
            PremiumMode.ForceEnable -> {
                args[enabledIndex] = java.lang.Boolean.TRUE
                args[visibleIndex] = java.lang.Boolean.TRUE
            }

            PremiumMode.Hide -> {
                args[enabledIndex] = java.lang.Boolean.FALSE
                args[visibleIndex] = java.lang.Boolean.FALSE
            }

            PremiumMode.Default -> {
                Unit
            }
        }
    }

    /**
     * The premium-gate helper ANDs Features with a US-country geofence.
     * Method letters drift between R8 builds; see
     * [TargetSet.premiumGateMethods] for the per-build mapping. Logically:
     *   editsEnabled    → features.editsEnabled
     *   scheduleEnabled → features.scheduleEnabled
     *   geofence        → smsCountry == "US"
     *   editsVisible    → features.editsVisible && geofence
     *   scheduleVisible → features.scheduleVisible && geofence
     *
     * Force geofence to true (defeat geofencing) and the relevant flag-pair
     * checks to true only for ForceEnable mode. Hide mode wants the button
     * gone, which the constructor-level override above already handles.
     */
    private fun patchPremiumGate(
        classLoader: ClassLoader,
        edit: PremiumMode,
        schedule: PremiumMode,
    ) {
        val helper = XposedHelpers.findClass(targets.premiumGateHelper.name, classLoader)
        val methods = targets.premiumGateMethods
        val forceTrue =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            }
        XposedBridge.hookAllMethods(helper, methods.geofence, forceTrue)
        if (edit == PremiumMode.ForceEnable) {
            XposedBridge.hookAllMethods(helper, methods.editsEnabled, forceTrue)
            XposedBridge.hookAllMethods(helper, methods.editsVisible, forceTrue)
        }
        if (schedule == PremiumMode.ForceEnable) {
            XposedBridge.hookAllMethods(helper, methods.scheduleEnabled, forceTrue)
            XposedBridge.hookAllMethods(helper, methods.scheduleVisible, forceTrue)
        }
        XposedBridge.log(
            "[$TAG] Premium gate (${targets.premiumGateHelper.name}) overrides installed " +
                "(edit=$edit, schedule=$schedule)",
        )
    }
}
