package com.example.mtga.config

import android.content.Context
import android.net.Uri
import com.example.mtga.SettingsActivity
import de.robv.android.xposed.XposedBridge

/**
 * Reads MTGA settings from inside the hooked Truth Social process.
 *
 * Why a ContentProvider? The historical mechanism (LSPosed mirroring
 * MODE_WORLD_READABLE prefs into a managed dir that `XSharedPreferences`
 * reads back) silently fails when LSPosed has `com.example.mtga` on its
 * denylist: LSPosed never runs its `getSharedPreferences` redirect for the
 * MTGA process, so the LSPosed-managed prefs dir stays empty and the host
 * sees `not readable; using defaults` forever. Reproducing:
 *
 * ```
 * E ReLSPosed: Process com.example.mtga is on denylist, cannot specialize
 * I MTGA: SettingsHolder: .../mtga_settings.xml not readable; using defaults
 * ```
 *
 * Fix: bypass `XSharedPreferences` and ask MTGA's own
 * [com.example.mtga.config.SettingsContentProvider] via the regular
 * `ContentResolver` IPC. Works regardless of LSPosed scope/denylist state.
 *
 * Cached for the lifetime of the host process: settings only change when
 * the user edits them in SettingsActivity and restarts Truth Social, so
 * read-once-at-init is sufficient. The host Application context is recorded
 * at hook init via [bind] so other hooks can start activities.
 */
internal object SettingsHolder {
    private const val PROVIDER_AUTHORITY = SettingsContentProvider.AUTHORITY
    private val PROVIDER_URI: Uri = SettingsContentProvider.URI_ALL

    private val cache: MutableMap<String, String> = HashMap()

    @Volatile private var ctx: Context? = null

    @Volatile private var loaded = false

    fun bind(context: Context) {
        ctx = context.applicationContext
        // Reload on every bind() so test harnesses can rebind. Production
        // wires this once per host launch.
        loaded = false
        ensureLoaded()
    }

    /** Host Application context. Available after [bind] runs (Application.onCreate). */
    fun appContext(): Context? = ctx

    fun read(
        key: String,
        default: Boolean,
    ): Boolean {
        ensureLoaded()
        return when (cache[key]?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    fun readString(
        key: String,
        default: String?,
    ): String? {
        ensureLoaded()
        return cache[key] ?: default
    }

    fun readRawString(
        key: String,
        default: String,
    ): String {
        ensureLoaded()
        return cache[key] ?: default
    }

    /**
     * Read a long-valued pref from the in-memory cache. Returns [default]
     * when the key is missing or its value isn't a parsable long.
     */
    fun readLong(
        key: String,
        default: Long,
    ): Long {
        ensureLoaded()
        return cache[key]?.toLongOrNull() ?: default
    }

    /**
     * One-shot query that bypasses the in-memory cache and hits the provider
     * directly. Used by [com.example.mtga.hooks.HostRestartHook] to detect
     * that the user just edited preferences and the host should be torn
     * down; the cache otherwise pins the at-launch snapshot.
     */
    fun readLongUncached(
        context: Context,
        key: String,
        default: Long,
    ): Long =
        try {
            context.contentResolver
                .query(PROVIDER_URI, arrayOf(key), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.toLongOrNull() ?: default else default }
                ?: default
        } catch (_: Throwable) {
            default
        }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val context = ctx ?: return // bind() not called yet
        loaded = true

        // ContentProvider — cheapest path; works when the host's <queries>
        // matches our intent-filter visibility trick (Truth Social v1.26.2+).
        // v1.24.x's <queries> only lists <package> entries, so we fall
        // through to XSharedPreferences for those builds.
        if (tryLoadViaProvider(context)) return

        // XSharedPreferences — relies on the LSPosed daemon's broad
        // file-read sepolicy to bypass `app_data_file` cross-UID
        // isolation. Requires MTGA off the LSPosed denylist AND the
        // `xposedsharedprefs=true` manifest flag (set in our manifest).
        if (tryLoadViaXSharedPreferences()) return

        // Direct file read — succeeds only on userdebug / permissive
        // SELinux. Kept as a last resort.
        if (tryLoadViaFile()) return

        XposedBridge.log("[MTGA] SettingsHolder: every load path failed — using defaults")
    }

    /**
     * Read MTGA's prefs via LSPosed's [de.robv.android.xposed.XSharedPreferences].
     * The LSPosed daemon mirrors any MTGA-side
     * `getSharedPreferences(MODE_WORLD_READABLE)` write to a managed dir
     * readable from any hooked process, sidestepping the SELinux
     * `app_data_file` isolation that blocks [tryLoadViaFile].
     *
     * Requires MTGA NOT to be on the LSPosed denylist (ReLSPosed defaults
     * to allowing modules through, but some installs auto-deny module
     * APKs as a Zygisk safeguard) and `xposedsharedprefs=true` in our
     * AndroidManifest meta-data.
     */
    private fun tryLoadViaXSharedPreferences(): Boolean {
        val xprefs =
            try {
                de.robv.android.xposed.XSharedPreferences("com.example.mtga", "mtga_settings")
            } catch (t: Throwable) {
                XposedBridge.log("[MTGA] SettingsHolder: XSharedPreferences ctor threw: ${t.message}")
                return false
            }
        runCatching { xprefs.makeWorldReadable() }
        xprefs.reload()
        val file = xprefs.file
        if (file == null || !file.canRead()) {
            XposedBridge.log(
                "[MTGA] SettingsHolder: XSharedPreferences not readable " +
                    "(file=${file?.absolutePath}). The most common cause is " +
                    "`com.example.mtga` being on the LSPosed denylist — " +
                    "open the LSPosed Manager, find MTGA under Denylist, and " +
                    "uncheck it. Then restart Truth Social.",
            )
            return false
        }
        val all = xprefs.all
        if (all.isEmpty()) {
            XposedBridge.log("[MTGA] SettingsHolder: XSharedPreferences returned empty map — using defaults")
            return true // empty is still a successful read (first-launch state)
        }
        for ((k, v) in all) {
            cache[k] = v.toString()
        }
        XposedBridge.log("[MTGA] SettingsHolder: loaded ${cache.size} keys via XSharedPreferences")
        return true
    }

    private fun tryLoadViaProvider(context: Context): Boolean {
        val cursor =
            try {
                context.contentResolver.query(PROVIDER_URI, null, null, null, null)
            } catch (t: Throwable) {
                XposedBridge.log("[MTGA] SettingsHolder: ContentResolver.query threw: ${t.javaClass.simpleName}: ${t.message}")
                return false
            }
        if (cursor == null) {
            XposedBridge.log(
                "[MTGA] SettingsHolder: provider $PROVIDER_AUTHORITY resolved to null cursor " +
                    "(probably package-visibility blocked) — trying file fallback",
            )
            return false
        }
        cursor.use { c ->
            if (!c.moveToFirst()) {
                XposedBridge.log("[MTGA] SettingsHolder: provider returned empty row — using defaults")
                return true // empty result is still a successful load
            }
            for (i in 0 until c.columnCount) {
                val key = c.getColumnName(i) ?: continue
                val value = c.getString(i) ?: continue
                cache[key] = value
            }
        }
        XposedBridge.log("[MTGA] SettingsHolder: loaded ${cache.size} keys via ContentProvider")
        return true
    }

    /**
     * Bypass XSharedPreferences (denylist-broken) and the ContentProvider
     * (package-visibility-blocked) by reading the prefs XML directly. The
     * file is at `/data/user/0/com.example.mtga/shared_prefs/mtga_settings.xml`;
     * on rooted devices with LSPosed installed the SELinux context normally
     * permits cross-package reads of the `app_data_file` type, but only if
     * filesystem perms allow it (the file is `0660 u0_aXXX`).
     *
     * If unreadable, accept defaults; the rest of the mod has sensible
     * fallbacks for each toggle.
     */
    private fun tryLoadViaFile(): Boolean {
        // Prefer the explicit export written by SettingsActivity — its
        // 0644 mode survives SharedPreferences's atomic-rename writer
        // which keeps resetting the canonical `shared_prefs/` copy to 0600.
        val candidates =
            listOf(
                java.io.File("/data/user/0/com.example.mtga/files/${SettingsActivity.EXPORT_FILE_NAME}"),
                java.io.File("/data/data/com.example.mtga/files/${SettingsActivity.EXPORT_FILE_NAME}"),
                java.io.File("/data/user/0/com.example.mtga/shared_prefs/mtga_settings.xml"),
                java.io.File("/data/data/com.example.mtga/shared_prefs/mtga_settings.xml"),
            )
        val file = candidates.firstOrNull { it.canRead() }
        if (file == null) {
            XposedBridge.log("[MTGA] SettingsHolder: no readable prefs file on disk")
            return false
        }
        return try {
            parsePrefsXml(file.readText())
            XposedBridge.log("[MTGA] SettingsHolder: loaded ${cache.size} keys via direct file read (${file.absolutePath})")
            true
        } catch (t: Throwable) {
            XposedBridge.log("[MTGA] SettingsHolder: direct file read failed: ${t.message}")
            false
        }
    }

    /** Parse Android's SharedPreferences XML format into [cache]. */
    private fun parsePrefsXml(xml: String) {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "boolean" -> {
                        val k = parser.getAttributeValue(null, "name")
                        val v = parser.getAttributeValue(null, "value")
                        if (k != null && v != null) cache[k] = v
                    }

                    "string" -> {
                        val k = parser.getAttributeValue(null, "name") ?: ""
                        val text = parser.nextText()
                        if (k.isNotEmpty()) cache[k] = text
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * Drop the in-memory cache. Future reads re-query the provider. Not
     * needed in production (settings only change when the user edits them
     * and restarts the host), but exposed for a future hot-reload feature.
     */
    @Suppress("unused")
    fun invalidate() {
        synchronized(this) {
            cache.clear()
            loaded = false
        }
    }
}
