# LSPosed loads MainHook by FQN from assets/xposed_init. SettingsActivity is
# launched from Truth Social's process via setClassName + explicit Intent.
# Without these keeps, R8 strips them and the module fails to load.
-keep class com.example.mtga.MainHook { *; }
-keep class com.example.mtga.SettingsActivity { *; }
-keep class com.example.mtga.config.SettingsContentProvider { *; }

# MainHook references every hook class directly, so R8 keeps them reachable
# either way; this rule preserves their original names so Xposed logs
# (`<hook.name> applied/failed`) and stack traces stay diagnosable.
-keep class com.example.mtga.hooks.** { *; }

# Settings / SettingKeys / PremiumMode / FeatureOverride are touched cross-
# process via the ContentProvider; field names matter to the IPC contract.
-keep class com.example.mtga.common.** { *; }
-keep class com.example.mtga.config.** { *; }

# Compose + Material 3 components used by SettingsActivity are referenced
# via @Composable resolution which R8 already understands; no extra keep
# rules needed. Just suppress noisy warnings.
-dontwarn androidx.compose.**
-dontwarn com.github.alorma.compose.settings.**
-dontwarn sh.calvin.reorderable.**
