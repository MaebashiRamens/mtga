package com.example.mtga.hooks

import android.app.Activity
import android.content.Intent
import android.view.MotionEvent
import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * In-app entry point for MTGA settings.
 *
 * Launches the MTGA SettingsActivity from inside Truth Social. The hook on
 * `dispatchTouchEvent` watches for a triple-tap at the very top-left corner
 * (the avatar / drawer icon area) within a short window.
 *
 * Choosing the avatar corner is intentional: a single tap opens the drawer
 * (stock behaviour, untouched), so two more rapid taps don't break anything
 * — the drawer is already open by the time the third tap registers. The
 * counter resets when the gap exceeds the window or the touch lands outside
 * the trigger zone.
 */
class InAppSettingsHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "InAppSettings"

    private var tapCount = 0
    private var lastTapMs = 0L

    override fun hook(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            classLoader,
            "dispatchTouchEvent",
            MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val event = param.args[0] as? MotionEvent ?: return
                    if (event.actionMasked != MotionEvent.ACTION_DOWN) return
                    if (!isInTriggerZone(event)) {
                        resetTaps()
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastTapMs > TAP_WINDOW_MS) {
                        tapCount = 1
                    } else {
                        tapCount++
                    }
                    lastTapMs = now
                    if (tapCount >= 3) {
                        resetTaps()
                        launchSettings(param.thisObject as? Activity ?: return)
                    }
                }
            },
        )
        XposedBridge.log("[$TAG] InAppSettings: triple-tap top-left to open MTGA settings")
    }

    private fun isInTriggerZone(event: MotionEvent): Boolean {
        // top-left ~120dp x 120dp (avatar + drawer-icon hit area).
        return event.x in 0f..TRIGGER_ZONE_PX && event.y in 0f..TRIGGER_ZONE_PX
    }

    private fun resetTaps() {
        tapCount = 0
        lastTapMs = 0
    }

    private fun launchSettings(activity: Activity) {
        try {
            val intent =
                Intent().apply {
                    setClassName("com.example.mtga", "com.example.mtga.SettingsActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            activity.startActivity(intent)
            XposedBridge.log("[$TAG] InAppSettings launched MTGA settings")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] InAppSettings launch failed: ${t.message}")
        }
    }

    companion object {
        private const val TAP_WINDOW_MS = 600L

        // Top-left zone in px. 360 ~ 120dp on a 3x density display — the
        // avatar plus a little breathing room.
        private const val TRIGGER_ZONE_PX = 360f
    }
}
