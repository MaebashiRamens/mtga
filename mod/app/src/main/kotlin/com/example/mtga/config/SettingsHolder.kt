package com.example.mtga.config

import android.content.Context
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

/**
 * Reads MTGA settings from inside the hooked Truth Social process.
 *
 * MTGA's [SettingsActivity] writes prefs with `MODE_WORLD_READABLE`. On a
 * LSPosed-rooted device, LSPosed redirects that write into its own managed
 * directory at `/data/misc/<lsposed-id>/prefs/com.example.mtga/`, where
 * [XSharedPreferences] in the hooked process can read it back. No `su` /
 * file-mirroring required.
 *
 * For this to work the module must be in its own LSPosed scope (handled by
 * the `xposed_scope` array in the manifest).
 *
 * Cached for the lifetime of the host process. The host Application context
 * is recorded once at hook init via [bind] so it can be handed to other
 * hooks that need to start activities.
 */
internal object SettingsHolder {
    private const val MODULE_PACKAGE = "com.example.mtga"

    private val boolCache: MutableMap<String, Boolean> = HashMap()
    private val stringCache: MutableMap<String, String> = HashMap()

    @Volatile private var ctx: Context? = null

    @Volatile private var loaded = false

    fun bind(context: Context) {
        ctx = context.applicationContext
    }

    /** Host Application context. Available after [bind] runs (Application.onCreate). */
    fun appContext(): Context? = ctx

    fun read(
        key: String,
        default: Boolean,
    ): Boolean {
        ensureLoaded()
        return boolCache.getOrDefault(key, default)
    }

    fun readString(
        key: String,
        default: String?,
    ): String? {
        ensureLoaded()
        return stringCache[key] ?: default
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val prefs =
            try {
                XSharedPreferences(MODULE_PACKAGE, Settings.PREFS_NAME).apply { makeWorldReadable() }
            } catch (t: Throwable) {
                XposedBridge.log("[MTGA] SettingsHolder: XSharedPreferences open failed: ${t.message}")
                return
            }
        if (!prefs.file.canRead()) {
            XposedBridge.log("[MTGA] SettingsHolder: ${prefs.file.absolutePath} not readable; using defaults")
            return
        }
        for (toggle in Settings.toggles) {
            boolCache[toggle.key] = prefs.getBoolean(toggle.key, toggle.defaultOn)
        }
        // Skip unset keys: caching null would shadow the entry's defaultMode
        // when callers later resolve via PremiumMode.fromStorage(null) → Default.
        for (entry in Settings.premiumModes) {
            prefs.getString(entry.key, null)?.let { stringCache[entry.key] = it }
        }
        XposedBridge.log("[MTGA] SettingsHolder: loaded via XSharedPreferences (${prefs.file.absolutePath})")
    }
}
