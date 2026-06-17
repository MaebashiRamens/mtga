package com.example.mtga.common

/**
 * Single source of truth for the SharedPreferences key names that drive the
 * MTGA feature toggles. Consumed only by the mod/app runtime hooks:
 * SettingsActivity (writes) and SettingsHolder (reads). The patches vector
 * does not reference these keys — a patch's inclusion is decided structurally
 * (`use = true/false` when assembling the `.rvp`), not via any PatchOption
 * keyed on a SettingKeys constant.
 */
object SettingKeys {
    const val PREFS_NAME = "mtga_settings"

    const val AdBlock = "ad_block"
    const val AnalyticsBlock = "analytics_block"
    const val IntegrityBypass = "integrity_bypass"
    const val HideForYou = "hide_for_you"
    const val HideHelpCenter = "hide_help_center"
    const val HideTruthGems = "hide_truth_gems"
    const val HideTruthPlus = "hide_truth_plus"
    const val HideAiTab = "hide_ai_tab"
    const val DisableSearchAi = "disable_search_ai"
    const val DisableAlertSwipe = "disable_alert_swipe"
    const val ClearAlertBadge = "clear_alert_badge"
    const val EnableTv = "enable_tv"

    // Premium-feature buttons (post editing, post scheduling).
    // Three-state: each feature has a [PremiumMode] selecting Default / ForceEnable / Hide.
    // We persist as a string. Mutually exclusive by design.
    const val PostEditMode = "post_edit_mode"
    const val PostScheduleMode = "post_schedule_mode"
    const val BlockTruthPlusUpsell = "block_truth_plus_upsell"

    // Bottom-bar tab reorder (v1.26.2+; the older builds use a dynamic tab
    // list and aren't affected). The order string is a comma-separated list
    // of route ids; unknown routes are appended at the end in their original
    // order so the feature degrades safely if the user supplies a partial list.
    const val ReorderBottomBar = "reorder_bottom_bar"
    const val BottomBarTabOrder = "bottom_bar_tab_order"
    const val DefaultBottomBarTabOrder = "feeds,discover,alerts,groups,predictions"

    // v1.26.2+ home-feed additions hidden by dedicated hooks rather than the
    // server-side Features ctor override (which the server can still ignore).
    const val HideTopBannerAd = "hide_top_banner_ad"
    const val HideLiveCarousel = "hide_live_carousel"

    // Append "-mtga-patched" to the Truth Social versionName at runtime so
    // analytics / About screens advertise the modded build. Mirrors the
    // build-time [MtgaPatchedSuffixPatch] revanced patch for users who run
    // MTGA via LSPosed instead of a .rvp-patched APK.
    const val AppendMtgaSuffix = "append_mtga_suffix"

    /**
     * `currentTimeMillis()` snapshot, written by SettingsActivity on every
     * `onStop`. The Truth Social hook reads it on cold start and on every
     * `Activity.onResume`; when it changes between two reads, the host
     * process kills itself so the next launch picks up freshly-saved prefs.
     * Auto-restart on return from MTGA Settings without root.
     */
    const val RestartMarker = "restart_marker"

    // Per-field Features overrides. Each key persists a [FeatureOverride]
    // (Default / ForceTrue / ForceFalse). Constructor-argument indices follow
    // Truth Social v1.27.0's `Features` data class:
    //   0: tvEnabled (Z)              1: forYouEnabled (Z)
    //   2: editsEnabled (Boolean)     3: editsVisible (Boolean)
    //   4: scheduleEnabled (Boolean)  5: scheduleVisible (Boolean)
    //   6: gemsEnabled (Boolean)      7: gemsVisible (Boolean)
    //   8: predictionsEnabled (Boolean, added v1.26.2)
    //   9: videoScrollingEnabled (Boolean, added v1.26.2)
    //  10: liveContentCarouselEnabled (Boolean, added v1.27.0)
    // Indices 0 and 1 are primitive `boolean`; 2..10 are nullable Boolean.
    const val FeatureTvEnabled = "feature_tv_enabled"
    const val FeatureForYouEnabled = "feature_for_you_enabled"
    const val FeatureEditsEnabled = "feature_edits_enabled"
    const val FeatureEditsVisible = "feature_edits_visible"
    const val FeatureScheduleEnabled = "feature_schedule_enabled"
    const val FeatureScheduleVisible = "feature_schedule_visible"
    const val FeatureGemsEnabled = "feature_gems_enabled"
    const val FeatureGemsVisible = "feature_gems_visible"
    const val FeaturePredictionsEnabled = "feature_predictions_enabled"
    const val FeatureVideoScrollingEnabled = "feature_video_scrolling_enabled"
    const val FeatureLiveContentCarouselEnabled = "feature_live_content_carousel_enabled"
}

/** Per-feature behavior selector for premium-gated buttons. */
enum class PremiumMode(
    val storageValue: String,
) {
    /** Stock app behavior: button visible, click opens Truth+ upsell. */
    Default("default"),

    /** Force-enable: button visible, click bypasses upsell (server may still reject). */
    ForceEnable("force_enable"),

    /** Hide the button entirely so it cannot be clicked. */
    Hide("hide"),
    ;

    companion object {
        fun fromStorage(value: String?): PremiumMode = values().firstOrNull { it.storageValue == value } ?: Default
    }
}

/**
 * Three-state override for an individual field on Truth Social's `Features`
 * data class. Lets the user flip a server-supplied boolean independently of
 * higher-level toggles (`enable_tv`, `post_edit_mode`) that may touch the
 * same fields.
 *
 *  - [Default]: leave the server value untouched.
 *  - [ForceTrue]: overwrite the ctor argument with `true` / Boolean.TRUE.
 *  - [ForceFalse]: overwrite the ctor argument with `false` / Boolean.FALSE.
 */
enum class FeatureOverride(
    val storageValue: String,
) {
    Default("default"),
    ForceTrue("force_true"),
    ForceFalse("force_false"),
    ;

    companion object {
        fun fromStorage(value: String?): FeatureOverride = values().firstOrNull { it.storageValue == value } ?: Default
    }
}
