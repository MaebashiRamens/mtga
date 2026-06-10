package com.example.mtga.hooks

import android.app.Activity
import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetResolver
import com.example.mtga.config.SettingsHolder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Auto-restart Truth Social when the user finishes editing MTGA Settings.
 *
 * Without this hook the user has to manually force-stop and reopen Truth
 * Social: `am force-stop` needs root the host process doesn't have, and
 * `killBackgroundProcesses` on cross-package targets is a no-op on modern
 * Android.
 *
 * Approach:
 *   1. At install, snapshot [SettingKeys.RestartMarker] (a wall-clock
 *      timestamp written by [com.example.mtga.SettingsActivity.onStop]).
 *   2. Hook `Activity.onResume`. On each resume, re-query the marker. If it
 *      advanced, the user edited prefs while we were paused; kill our own
 *      process ([Process.killProcess] on `myPid()` needs no privileges).
 *   3. Android relaunches Truth Social when the user lands on a Truth
 *      Social activity that needs the process — which is happening now,
 *      since onResume fired. The relaunch hits MainHook with the
 *      freshly-saved prefs.
 *
 * Throttled by a per-resume re-read. ContentResolver.query on the MTGA
 * provider is microseconds-cheap and Activity.onResume is rare enough that
 * the cost is invisible.
 */
class HostRestartHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "HostRestart"

    @Volatile private var lastSeenMarker: Long = 0L

    override fun hook(classLoader: ClassLoader) {
        // Initial snapshot. Anything the user saves after this point bumps
        // the marker above this stored value.
        lastSeenMarker = SettingsHolder.readLong(SettingKeys.RestartMarker, 0L)
        XposedBridge.log("[$TAG] HostRestart: initial marker=$lastSeenMarker")

        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            classLoader,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    // Re-read straight from the provider; bypass the cache
                    // SettingsHolder normally exposes.
                    val current = SettingsHolder.readLongUncached(activity, SettingKeys.RestartMarker, lastSeenMarker)
                    if (current > lastSeenMarker) {
                        XposedBridge.log("[$TAG] HostRestart: marker advanced ($lastSeenMarker -> $current); killing host process")
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            },
        )
        XposedBridge.log("[$TAG] HostRestart: listener installed")
    }
}
