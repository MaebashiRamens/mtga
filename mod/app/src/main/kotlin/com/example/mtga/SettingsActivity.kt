package com.example.mtga

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetSet
import com.example.mtga.common.Targets
import com.example.mtga.config.SettingItem
import com.example.mtga.config.Settings
import com.example.mtga.ui.MtgaSettingsScreen

/**
 * Host activity for the MTGA settings screen. Persistence and IPC contracts
 * stay identical to the View-based predecessor — only the UI layer moved to
 * Jetpack Compose + Material 3. See [com.example.mtga.ui.MtgaSettingsScreen].
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Discard any saved view-state from a previous instance: we re-read
        // every row from the persisted prefs file, and a stale saved
        // ToggleableState would briefly render the wrong selection.
        super.onCreate(null)
        title = "MTGA settings"

        val prefs = openWorldReadablePrefs()
        migrateLegacyPrivatePrefs(prefs)
        runPremiumDefaultMigration(prefs)
        // Register AFTER the one-shot migrations so their writes don't count as
        // a user edit. From here, only a genuine settings change arms the
        // host-restart marker in [onStop].
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        val activeTargets = resolveInstalledTargetSet()

        setContent {
            MtgaSettingsScreen(prefs = prefs, targets = activeTargets)
        }
    }

    /**
     * Set when the user changes any real setting (not our bookkeeping keys),
     * so [onStop] only requests a host restart when something actually changed
     * — merely opening and closing MTGA Settings should not kill the host.
     */
    private var settingsChanged = false

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key != SettingKeys.RestartMarker && !key.startsWith("_migration_")) {
                settingsChanged = true
            }
        }

    override fun onDestroy() {
        runCatching { openWorldReadablePrefs().unregisterOnSharedPreferenceChangeListener(prefsListener) }
        super.onDestroy()
    }

    /**
     * Stamp [SettingKeys.RestartMarker] with the current wall-clock time so
     * [com.example.mtga.hooks.HostRestartHook] can detect that the user
     * finished editing settings. The hook reads this on every
     * `Activity.onResume`; when it differs from the value cached at process
     * start, the process kills itself and Android respawns Truth Social with
     * the new preferences.
     *
     * Triggered from [onStop] (not [onPause]) so a transient pause — config
     * change, system overlay — doesn't request a host restart.
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing || !isChangingConfigurations) {
            if (!settingsChanged) {
                android.util.Log.i("MTGA-Settings", "onStop: no settings changed; skipping host restart")
                markPrefsFileWorldReadable()
                return
            }
            val prefs = openWorldReadablePrefs()
            // Synchronous commit so the file is on disk before the chmod
            // in [markPrefsFileWorldReadable] runs.
            prefs.edit().putLong(SettingKeys.RestartMarker, System.currentTimeMillis()).commit()
            settingsChanged = false
            android.util.Log.i("MTGA-Settings", "onStop: settings changed; bumped restart marker")
            markPrefsFileWorldReadable()
        }
    }

    /**
     * Mirror prefs to `files/mtga_settings_export.xml` with mode 0644 so
     * [com.example.mtga.config.SettingsHolder]'s file fallback can read
     * them from the hooked process. We can't reuse the canonical
     * `shared_prefs/` file: SharedPreferences's atomic-rename writer
     * resets it to 0600 on every commit, dropping any chmod we apply.
     *
     * Path traversal also needs `+x` on every directory between
     * `/data/data/<pkg>` and the export file; effective only on devices
     * where the sepolicy permits cross-app `app_data_file` reads.
     */
    private fun markPrefsFileWorldReadable() {
        runCatching { dataDir.setExecutable(true, false) }
        runCatching { filesDir.setExecutable(true, false) }

        val source = java.io.File(dataDir, "shared_prefs/${Settings.PREFS_NAME}.xml")
        if (!source.exists()) return
        val exportFile = java.io.File(filesDir, EXPORT_FILE_NAME)
        try {
            source.copyTo(exportFile, overwrite = true)
            exportFile.setReadable(true, false)
            exportFile.setWritable(true, true)
        } catch (t: Throwable) {
            android.util.Log.w("MTGA-Settings", "prefs export to ${exportFile.absolutePath} failed: ${t.message}")
        }
    }

    /**
     * Find the [TargetSet] for the Truth Social build installed on this
     * device. We can't query the host directly (different process);
     * `PackageManager` exposes the versionCode of any installed package. If
     * Truth Social is missing or the versionCode isn't calibrated, fall back
     * to `Targets.latest` so every toggle stays visible (the hook itself
     * no-ops gracefully via [FallbackResolver]).
     */
    private fun resolveInstalledTargetSet(): TargetSet {
        val pm = packageManager
        val pi =
            runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo("com.truthsocial.android.app", 0)
            }.getOrNull() ?: return Targets.latest
        @Suppress("DEPRECATION")
        val installedCode = pi.versionCode
        return Targets.forVersionCode(installedCode) ?: Targets.latest
    }

    /**
     * MODE_WORLD_READABLE throws SecurityException on stock Android since
     * API 24. LSPosed v1.x+ hooks the StrictMode check away for Xposed
     * modules, so the call succeeds on a rooted+LSPosed install and the
     * write lands in LSPosed's managed prefs dir where `XSharedPreferences`
     * in the hooked process can read it. Fall back to MODE_PRIVATE if
     * LSPosed isn't present so a developer running `am start` against the
     * activity still gets a working UI (the hooks won't see writes, but
     * they aren't running in that scenario).
     */
    @Suppress("DEPRECATION")
    private fun openWorldReadablePrefs(): SharedPreferences {
        val prefs =
            try {
                getSharedPreferences(Settings.PREFS_NAME, Context.MODE_WORLD_READABLE)
            } catch (_: SecurityException) {
                getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
            }
        // Idempotent — [onStop] also runs this in case the file didn't
        // exist on first access.
        markPrefsFileWorldReadable()
        return prefs
    }

    /**
     * One-shot copy of pre-`xposedsharedprefs` MODE_PRIVATE prefs into
     * the LSPosed-managed world-readable mirror. Without this, users who
     * upgrade from an older MTGA would have to manually re-toggle every
     * setting before the host process saw any change.
     */
    private fun migrateLegacyPrivatePrefs(prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATION_LEGACY_PREFS_KEY, false)) return
        // LSPosed's xposedsharedprefs redirector hooks every
        // getSharedPreferences call regardless of mode, so the legacy
        // MODE_PRIVATE file is unreachable through the SDK. Parse the
        // XML ourselves.
        val legacyFile = java.io.File(dataDir, "shared_prefs/${Settings.PREFS_NAME}.xml")
        if (!legacyFile.exists() || legacyFile.length() == 0L) {
            prefs.edit().putBoolean(MIGRATION_LEGACY_PREFS_KEY, true).commit()
            return
        }
        val editor = prefs.edit()
        var copied = 0
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(legacyFile.inputStream(), null)
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val name = parser.getAttributeValue(null, "name") ?: ""
                    val raw = parser.getAttributeValue(null, "value")
                    if (name.isNotEmpty() && !prefs.contains(name)) {
                        val applied =
                            when (parser.name) {
                                "boolean" -> {
                                    raw?.toBooleanStrictOrNull()?.let { editor.putBoolean(name, it); true } ?: false
                                }
                                "int" -> raw?.toIntOrNull()?.let { editor.putInt(name, it); true } ?: false
                                "long" -> raw?.toLongOrNull()?.let { editor.putLong(name, it); true } ?: false
                                "float" -> raw?.toFloatOrNull()?.let { editor.putFloat(name, it); true } ?: false
                                "string" -> {
                                    // <string> uses element text, not the value attr
                                    val text = parser.nextText()
                                    editor.putString(name, text)
                                    true
                                }
                                else -> false
                            }
                        if (applied) copied++
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            android.util.Log.w("MTGA-Settings", "migrateLegacyPrivatePrefs parse failed: ${t.message}")
        }
        editor.putBoolean(MIGRATION_LEGACY_PREFS_KEY, true).commit()
        android.util.Log.i(
            "MTGA-Settings",
            "migrateLegacyPrivatePrefs: copied $copied keys from legacy XML → world-readable mirror",
        )
    }

    /**
     * One-shot migration: clear leftover premium-mode values so the entry's
     * defaultMode (Hide) takes effect and any Force-enable choice now goes
     * through the risk-warning dialog. Runs once per install, gated by
     * [MIGRATION_PREMIUM_DEFAULT_KEY].
     */
    private fun runPremiumDefaultMigration(prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATION_PREMIUM_DEFAULT_KEY, false)) return
        prefs
            .edit()
            .remove(SettingKeys.PostEditMode)
            .remove(SettingKeys.PostScheduleMode)
            .putBoolean(MIGRATION_PREMIUM_DEFAULT_KEY, true)
            .commit()
    }

    companion object {
        private const val MIGRATION_PREMIUM_DEFAULT_KEY = "_migration_premium_default_v1"
        private const val MIGRATION_LEGACY_PREFS_KEY = "_migration_legacy_prefs_v1"

        /** World-readable prefs mirror — see [markPrefsFileWorldReadable]. */
        const val EXPORT_FILE_NAME = "mtga_settings_export.xml"

        /**
         * Whether a [SettingItem] should be visible for the given Truth
         * Social build. Mirrors the predicate from the View-based version so
         * the filtering rules don't drift between the activity and the
         * Composable.
         */
        internal fun isItemSupported(
            item: SettingItem,
            targets: TargetSet,
        ): Boolean =
            when (item) {
                is SettingItem.Bool -> item.toggle.supportedFor(targets)
                is SettingItem.Mode -> item.entry.supportedFor(targets)
                is SettingItem.Override -> item.entry.supportedFor(targets)
            }

        /**
         * Human-readable label for a bottom-bar route id. The id (`"feeds"`,
         * `"alerts"`) survives R8 and is what we persist; the user sees the
         * host's tab title, so the reorder UI maps to that.
         */
        internal fun routeLabel(route: String): String =
            when (route) {
                "feeds" -> "Home"
                "discover" -> "Discover"
                "groups" -> "Groups"
                "chats" -> "Messages"
                "predictions" -> "Predictions"
                "alerts" -> "Alerts"
                else -> route
            }
    }
}
