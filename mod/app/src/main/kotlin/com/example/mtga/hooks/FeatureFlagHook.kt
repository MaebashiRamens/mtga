package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.PremiumMode
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetSet
import com.example.mtga.config.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Force on the client-side Truth+ feature gates.
 *
 * Each premium feature has a 3-state mode:
 *  - Default     : leave stock app behavior (button visible, click → upsell)
 *  - ForceEnable : button visible, click bypasses upsell (server may still reject)
 *  - Hide        : button hidden in the post composer
 *
 * The Hide mode here is implemented by patching the Features fields to false
 * (so the compose-time `if (Features.editsVisible) renderEditButton()` decides
 * to render nothing). ForceEnable patches them to true, plus the L6.U
 * helpers (which combine Features with a US-country geofence).
 *
 * This only flips client-side UI gates. The server may still reject the
 * actual schedule/edit operation if the account has no Truth+ subscription.
 */
class FeatureFlagHook(
    targets: TargetSet,
) : BaseHook(targets) {
    override val name = "FeatureFlag"

    override fun hook(classLoader: ClassLoader) {
        val tv = Settings.isOn(SettingKeys.EnableTv)
        val edit = Settings.premiumModeOf(SettingKeys.PostEditMode)
        val schedule = Settings.premiumModeOf(SettingKeys.PostScheduleMode)
        if (!tv && edit == PremiumMode.Default && schedule == PremiumMode.Default) {
            XposedBridge.log("[$TAG] No feature flags requested; skipping")
            return
        }

        patchFeaturesConstructor(classLoader, tv, edit, schedule)
        if (edit == PremiumMode.ForceEnable || schedule == PremiumMode.ForceEnable) {
            patchPremiumGate(classLoader, edit, schedule)
        }
    }

    /**
     * Features ctor declaration order in v1.24.8:
     *   0: tvEnabled        Z
     *   1: forYouEnabled    Z         — never overridden here (For You is hidden separately)
     *   2: editsEnabled     LBoolean;
     *   3: editsVisible     LBoolean;
     *   4: scheduleEnabled  LBoolean;
     *   5: scheduleVisible  LBoolean;
     *   6: gemsEnabled      LBoolean;
     *   7: gemsVisible      LBoolean;
     *
     * For each feature:
     *   ForceEnable → both *Enabled and *Visible to true
     *   Hide        → both to false (button disappears entirely)
     *   Default     → leave the original API value
     */
    private fun patchFeaturesConstructor(
        classLoader: ClassLoader,
        tv: Boolean,
        edit: PremiumMode,
        schedule: PremiumMode,
    ) {
        val featuresClass = XposedHelpers.findClass(FEATURES_CLASS, classLoader)
        XposedBridge.hookAllConstructors(
            featuresClass,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args
                    if (args.size < 8) return // skip synthetic shorter overloads
                    if (tv) args[0] = true
                    applyMode(edit, args, enabledIndex = 2, visibleIndex = 3)
                    applyMode(schedule, args, enabledIndex = 4, visibleIndex = 5)
                }
            },
        )
        XposedBridge.log("[$TAG] Features constructor overrides installed (tv=$tv, edit=$edit, schedule=$schedule)")
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
     * `L6.U` combines Features with a US-country geofence:
     *   a → editsEnabled
     *   c → scheduleEnabled
     *   d → smsCountry == "US"
     *   e → editsVisible    && d(user)
     *   g → scheduleVisible && d(user)
     *
     * Force d() to true (defeat geofencing) and the relevant flag-pair checks
     * to true ONLY for ForceEnable mode (Hide mode wants the button gone, so
     * the constructor-level override above already handles it).
     */
    private fun patchPremiumGate(
        classLoader: ClassLoader,
        edit: PremiumMode,
        schedule: PremiumMode,
    ) {
        val helper = XposedHelpers.findClass(targets.premiumGateHelper.name, classLoader)
        val forceTrue =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            }
        XposedBridge.hookAllMethods(helper, "d", forceTrue) // geofence
        if (edit == PremiumMode.ForceEnable) {
            XposedBridge.hookAllMethods(helper, "a", forceTrue)
            XposedBridge.hookAllMethods(helper, "e", forceTrue)
        }
        if (schedule == PremiumMode.ForceEnable) {
            XposedBridge.hookAllMethods(helper, "c", forceTrue)
            XposedBridge.hookAllMethods(helper, "g", forceTrue)
        }
        XposedBridge.log("[$TAG] L6.U geofence + capability overrides installed (edit=$edit, schedule=$schedule)")
    }

    companion object {
        private const val FEATURES_CLASS = "com.truthsocial.app.data.models.Features"
    }
}
