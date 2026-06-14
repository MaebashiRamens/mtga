package com.example.mtga.config

import com.example.mtga.common.FeatureOverride
import com.example.mtga.common.PremiumMode
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetSet

/**
 * Feature toggle catalog used by the runtime hooks and SettingsActivity.
 * Pref keys live in the shared :common module so patches/ stays in sync.
 *
 * [SettingsCategory] groups items for UI display; categories with
 * `isAdvanced = true` are rendered under an "Advanced" expandable section.
 */
object Settings {
    const val PREFS_NAME = SettingKeys.PREFS_NAME

    val categories: List<SettingsCategory> =
        listOf(
            SettingsCategory(
                "Privacy & Network",
                listOf(
                    SettingItem.Bool(
                        Toggle(SettingKeys.AdBlock, true, "Block ads", "Hides /truth/ads responses, AdQueueManager, AdImpressionManager"),
                    ),
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.AnalyticsBlock,
                            true,
                            "Block analytics",
                            "Disables Firebase Analytics, Crashlytics, AppAnalyticsManager",
                        ),
                    ),
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.IntegrityBypass,
                            true,
                            "Bypass Play Integrity",
                            "Skips integrity headers on Like/Status/Reaction/etc.",
                        ),
                    ),
                ),
            ),
            SettingsCategory(
                "UI Cleanup",
                listOf(
                    SettingItem.Bool(Toggle(SettingKeys.HideForYou, true, "Hide For You tab", "Filters for_you/recommended feeds")),
                    SettingItem.Bool(Toggle(SettingKeys.HideHelpCenter, true, "Hide Help Center", "Removes the sidebar Help Center entry")),
                    SettingItem.Bool(
                        Toggle(SettingKeys.HideTruthGems, true, "Hide Truth Gems", "Removes the gem badge and the Truth Gems banner"),
                    ),
                    SettingItem.Bool(
                        Toggle(SettingKeys.HideTruthPlus, true, "Hide TRUTH+ button", "Removes the upsell button from the top app bar"),
                    ),
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.HideAiTab,
                            true,
                            "Hide AI tab",
                            "Removes the Truth Search AI tab from the bottom bar",
                            // The dedicated AI bottom-bar tab class only
                            // exists on v1.24.x / v1.26.1. v1.26.2 dropped
                            // it (AI is reached via Discover), so this hook
                            // has no work to do and the toggle is hidden.
                            supportedFor = { it.bottomNavAiTab != null },
                        ),
                    ),
                    SettingItem.Bool(
                        Toggle(SettingKeys.DisableSearchAi, true, "Disable Search AI", "Neutralizes SearchAIUseCase invocations"),
                    ),
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.HideTopBannerAd,
                            true,
                            "Hide top banner ad",
                            "Removes the sponsored \"Proudly sponsored by Truth Social\" card at the top of the home feed",
                            supportedFor = {
                                it.embeddedAnnouncement != null ||
                                    it.nonNativeAdRenderer != null ||
                                    it.homeAnnouncementRenderer != null
                            },
                        ),
                    ),
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.HideLiveCarousel,
                            true,
                            "Hide live content carousel",
                            "Removes the livestream avatar carousel at the top of the home feed",
                            supportedFor = { it.liveContentCarousel != null },
                        ),
                    ),
                ),
            ),
            SettingsCategory(
                "Alerts",
                listOf(
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.DisableAlertSwipe,
                            true,
                            "Disable swipe-to-delete",
                            "Prevents accidental delete by swipe on the alerts list",
                        ),
                    ),
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.ClearAlertBadge,
                            true,
                            "Auto-clear alerts badge",
                            "Resets the unread alerts count when entering the alerts tab",
                        ),
                    ),
                ),
            ),
            SettingsCategory(
                "Branding",
                listOf(
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.AppendMtgaSuffix,
                            true,
                            "Tag versionName with -mtga-patched",
                            "Appends \"-mtga-patched\" to Truth Social's runtime versionName " +
                                "(visible to analytics, crashlytics, and any code reading via " +
                                "PackageManager). Mirrors the revanced .rvp suffix patch for " +
                                "LSPosed users.",
                            supportedFor = { it.appBuildInfo != null },
                        ),
                    ),
                ),
            ),
            SettingsCategory(
                "Truth+ (premium)",
                listOf(
                    SettingItem.Bool(
                        Toggle(
                            SettingKeys.BlockTruthPlusUpsell,
                            true,
                            "Block Truth+ upsell",
                            "Suppresses 'This feature is available with Truth+' modal sheets",
                        ),
                    ),
                    SettingItem.Mode(
                        PremiumModeEntry(SettingKeys.PostEditMode, PremiumMode.Hide, "Post editing", "Edit your truths after posting"),
                    ),
                    SettingItem.Mode(
                        PremiumModeEntry(
                            SettingKeys.PostScheduleMode,
                            PremiumMode.Hide,
                            "Post scheduling",
                            "Schedule a truth for a future time",
                        ),
                    ),
                ),
            ),
            SettingsCategory(
                "Experimental",
                isAdvanced = true,
                items =
                    listOf(
                        SettingItem.Bool(
                            Toggle(SettingKeys.EnableTv, false, "Enable Truth TV", "Forces Features.tvEnabled to true (server may reject)"),
                        ),
                        SettingItem.Bool(
                            Toggle(
                                SettingKeys.ReorderBottomBar,
                                false,
                                "Reorder bottom bar",
                                "Reorders the bottom-bar tabs per the list below. Restart Truth Social after editing.",
                                // BottomBarReorderHook needs the v1.26.2+ static
                                // list shape (`Zc.j.a` / `cd.j.a`). Older builds
                                // hand back a fresh list from an instance method
                                // each call, so the reorder approach doesn't apply.
                                supportedFor = { it.bottomNavTabsStaticFields.isNotEmpty() },
                            ),
                        ),
                    ),
            ),
            // Per-field overrides for `Features` ctor arguments. Entries
            // that overlap with a higher-level toggle are hidden — the
            // higher-level toggle is the recommended control surface.
            SettingsCategory(
                "Feature flags",
                isAdvanced = true,
                items =
                    listOf(
                        SettingItem.Override(
                            FeatureOverrideEntry(
                                SettingKeys.FeaturePredictionsEnabled,
                                featureIndex = 8,
                                isNullableBoolean = true,
                                label = "predictionsEnabled",
                                description = "Predictions tab + predictions UI (v1.26.2+)",
                                // Older ctors lack this slot; FeatureFlagHook
                                // also skips per-arg writes for indices past
                                // args.size.
                                supportedFor = { it.buildId.versionCode >= 1256 },
                            ),
                        ),
                        SettingItem.Override(
                            FeatureOverrideEntry(
                                SettingKeys.FeatureVideoScrollingEnabled,
                                featureIndex = 9,
                                isNullableBoolean = true,
                                label = "videoScrollingEnabled",
                                description = "TikTok-style vertical video scrolling (v1.26.2+)",
                                supportedFor = { it.buildId.versionCode >= 1256 },
                            ),
                        ),
                    ),
            ),
        )

    val toggles: List<Toggle> =
        categories.flatMap { c ->
            c.items.filterIsInstance<SettingItem.Bool>().map { it.toggle }
        }

    val premiumModes: List<PremiumModeEntry> =
        categories.flatMap { c ->
            c.items.filterIsInstance<SettingItem.Mode>().map { it.entry }
        }

    val featureOverrides: List<FeatureOverrideEntry> =
        categories.flatMap { c ->
            c.items.filterIsInstance<SettingItem.Override>().map { it.entry }
        }

    fun isOn(key: String): Boolean = SettingsHolder.read(key, defaultOf(key))

    fun premiumModeOf(key: String): PremiumMode {
        val entry = premiumModes.firstOrNull { it.key == key }
        val default = entry?.defaultMode ?: PremiumMode.Default
        return PremiumMode.fromStorage(SettingsHolder.readString(key, default.storageValue))
    }

    fun featureOverrideOf(key: String): FeatureOverride =
        FeatureOverride.fromStorage(SettingsHolder.readString(key, FeatureOverride.Default.storageValue))

    /** Read a free-form string setting (e.g. the bottom-bar order). */
    fun getString(
        key: String,
        default: String,
    ): String = SettingsHolder.readRawString(key, default)

    private fun defaultOf(key: String): Boolean = toggles.firstOrNull { it.key == key }?.defaultOn ?: false
}

data class SettingsCategory(
    val title: String,
    val items: List<SettingItem>,
    /**
     * `true` when this category should live under the collapsible "Advanced"
     * section in [com.example.mtga.ui.MtgaSettingsScreen]. The hook layer is
     * agnostic to this flag — it only affects the Settings UI grouping.
     */
    val isAdvanced: Boolean = false,
)

sealed interface SettingItem {
    data class Bool(
        val toggle: Toggle,
    ) : SettingItem

    data class Mode(
        val entry: PremiumModeEntry,
    ) : SettingItem

    data class Override(
        val entry: FeatureOverrideEntry,
    ) : SettingItem
}

data class Toggle(
    val key: String,
    val defaultOn: Boolean,
    val label: String,
    val description: String,
    /**
     * Predicate over the installed Truth Social build's [TargetSet];
     * decides whether the toggle is rendered in SettingsActivity. `false`
     * hides the toggle (and skips running its hook) for builds where the
     * hook would be a guaranteed no-op. Defaults to "always shown".
     */
    val supportedFor: (TargetSet) -> Boolean = { _ -> true },
)

data class PremiumModeEntry(
    val key: String,
    val defaultMode: PremiumMode,
    val label: String,
    val description: String,
    val supportedFor: (TargetSet) -> Boolean = { _ -> true },
)

/**
 * Catalog entry for a `Features` constructor-argument override.
 *
 * @param key                pref key persisting the [FeatureOverride] choice
 * @param featureIndex       0-based ctor-argument index on `Features`.
 *                           [com.example.mtga.hooks.FeatureFlagHook] writes
 *                           directly into `MethodHookParam.args[featureIndex]`.
 * @param isNullableBoolean  `true` if the slot accepts boxed
 *                           `java.lang.Boolean` (indices 2..10 on v1.27.0);
 *                           `false` for primitive `boolean` (indices 0, 1).
 *                           Drives the choice between `true` /
 *                           `java.lang.Boolean.TRUE` so the Xposed args-array
 *                           write doesn't trip an AutoBoxing VerifyError.
 * @param label              short field name shown in MTGA Settings
 * @param description        explanatory text under the field name
 * @param supportedFor       predicate over the live [TargetSet]; hides
 *                           entries whose ctor index doesn't exist on that
 *                           build (e.g. `liveContentCarouselEnabled` only
 *                           exists v1.27.0+).
 */
data class FeatureOverrideEntry(
    val key: String,
    val featureIndex: Int,
    val isNullableBoolean: Boolean,
    val label: String,
    val description: String,
    val supportedFor: (TargetSet) -> Boolean = { _ -> true },
)
