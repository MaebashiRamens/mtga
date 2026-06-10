package com.example.mtga.config

import android.content.Context
import android.net.Uri
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
    private const val PROVIDER_AUTHORITY = "com.example.mtga.settings"
    private val PROVIDER_URI: Uri = Uri.parse("content://$PROVIDER_AUTHORITY/all")

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

        // Path 1: ContentProvider. Cheapest when it works. Blocked by
        // Android 11+ package-visibility filtering because Truth Social
        // didn't declare `<queries>` for `com.example.mtga` (we can't add
        // it to a third-party manifest), so this stays first but is
        // expected to fail on most modern builds.
        if (tryLoadViaProvider(context)) return

        // Path 2: direct filesystem read of MTGA's private prefs dir.
        // Almost always blocked by SELinux (`app_data_file` typing
        // prohibits cross-UID reads); only succeeds on rare devices where
        // the policy is relaxed.
        if (tryLoadViaFile()) return

        // Path 3: root shell. KernelSU Next / Magisk grants root to the
        // Truth Social process globally or per-app; `cat` of MTGA's prefs
        // file runs as `root`, bypassing SELinux app-data isolation. If
        // root isn't granted, this returns quickly with `su: not found`
        // or similar and we fall through.
        if (tryLoadViaSu()) return

        XposedBridge.log("[MTGA] SettingsHolder: every load path failed — using defaults")
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
        val candidates =
            listOf(
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

    /**
     * Read MTGA's prefs via a root shell. Works only on rooted devices
     * where `su` is granted to the Truth Social process (KernelSU Next /
     * Magisk per-app grant). No-op without root: the `ProcessBuilder`
     * exits non-zero almost immediately.
     *
     * Invokes `cat` (not a chain of shell pipes) to keep the surface small
     * and parse step identical to [tryLoadViaFile].
     */
    private fun tryLoadViaSu(): Boolean {
        val path = "/data/user/0/com.example.mtga/shared_prefs/mtga_settings.xml"
        val xml =
            try {
                val proc =
                    ProcessBuilder("su", "-c", "cat $path")
                        .redirectErrorStream(false)
                        .start()
                val output = proc.inputStream.bufferedReader().readText()
                // Cap at 5 s. Root prompts that aren't auto-approved
                // would otherwise wedge us at process start.
                val ok = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!ok) {
                    proc.destroy()
                    XposedBridge.log("[MTGA] SettingsHolder: su path timed out")
                    return false
                }
                if (proc.exitValue() != 0) {
                    XposedBridge.log("[MTGA] SettingsHolder: su path exit=${proc.exitValue()} (no root grant?)")
                    return false
                }
                output
            } catch (t: Throwable) {
                XposedBridge.log("[MTGA] SettingsHolder: su path threw: ${t.javaClass.simpleName}: ${t.message}")
                return false
            }
        if (xml.isBlank()) {
            XposedBridge.log("[MTGA] SettingsHolder: su path produced empty output")
            return false
        }
        return try {
            parsePrefsXml(xml)
            XposedBridge.log("[MTGA] SettingsHolder: loaded ${cache.size} keys via su shell")
            true
        } catch (t: Throwable) {
            XposedBridge.log("[MTGA] SettingsHolder: su-path XML parse failed: ${t.message}")
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
