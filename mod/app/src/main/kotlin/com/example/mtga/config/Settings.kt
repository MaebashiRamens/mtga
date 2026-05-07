package com.example.mtga.config

import com.example.mtga.common.PremiumMode
import com.example.mtga.common.SettingKeys

/**
 * Feature toggle catalog used by the runtime hooks and the SettingsActivity UI.
 * The pref keys come from the shared :common module so patches/ stays in sync.
 *
 * Items are grouped into [SettingsCategory]s purely for UI display; lookup by
 * key still goes through [isOn] / [premiumModeOf].
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
                        Toggle(SettingKeys.HideAiTab, true, "Hide AI tab", "Removes the Truth Search AI tab from the bottom bar"),
                    ),
                    SettingItem.Bool(
                        Toggle(SettingKeys.DisableSearchAi, true, "Disable Search AI", "Neutralizes SearchAIUseCase invocations"),
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
                listOf(
                    SettingItem.Bool(
                        Toggle(SettingKeys.EnableTv, false, "Enable Truth TV", "Forces Features.tvEnabled to true (server may reject)"),
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

    fun isOn(key: String): Boolean = SettingsHolder.read(key, defaultOf(key))

    fun premiumModeOf(key: String): PremiumMode {
        val entry = premiumModes.firstOrNull { it.key == key }
        val default = entry?.defaultMode ?: PremiumMode.Default
        return PremiumMode.fromStorage(SettingsHolder.readString(key, default.storageValue))
    }

    private fun defaultOf(key: String): Boolean = toggles.firstOrNull { it.key == key }?.defaultOn ?: false
}

data class SettingsCategory(
    val title: String,
    val items: List<SettingItem>,
)

sealed interface SettingItem {
    data class Bool(
        val toggle: Toggle,
    ) : SettingItem

    data class Mode(
        val entry: PremiumModeEntry,
    ) : SettingItem
}

data class Toggle(
    val key: String,
    val defaultOn: Boolean,
    val label: String,
    val description: String,
)

data class PremiumModeEntry(
    val key: String,
    val defaultMode: PremiumMode,
    val label: String,
    val description: String,
)
