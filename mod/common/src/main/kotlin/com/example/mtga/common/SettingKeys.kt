package com.example.mtga.common

/**
 * Single source of truth for the SharedPreferences key names that drive the
 * MTGA feature toggles. Used by:
 *  - mod/app: SettingsActivity (writes), SettingsHolder (reads)
 *  - patches: build-time toggle for whether to apply each patch
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
