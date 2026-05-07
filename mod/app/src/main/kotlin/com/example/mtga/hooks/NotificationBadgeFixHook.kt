package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetSet
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Fix the bottom-bar notification badge that does not refresh after the user
 * views their alerts.
 *
 * Truth Social's AlertsApi has no "mark all as read" endpoint. The badge count
 * comes from `alertsRepository.getAlertsBadgeCount()`, which mirrors the
 * server-side unread count. After viewing alerts the local app never clears
 * it — the bell keeps showing the count until something else updates the
 * StateFlow.
 *
 * Workaround: hook AppStateManagerImpl
 *  - c(menuItem) — fired when the user taps a bottom-bar tab. If the tab is
 *    the Alerts tab, call g(menuItem, 0) to clear the badge.
 *  - e(menuItem) — fired when the framework sets the selected tab; same logic.
 *
 * The user still sees alert items themselves; only the badge bubble is hidden.
 */
class NotificationBadgeFixHook(
    targets: TargetSet,
) : BaseHook(targets) {
    override val name = "NotificationBadgeFix"

    override fun hook(classLoader: ClassLoader) {
        val stateManagerClass = XposedHelpers.findClass(targets.appStateManager.name, classLoader)
        val alertsTabClass = XposedHelpers.findClass(targets.bottomNavAlertsTab.name, classLoader)

        val clearBadgeHook =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val menuItem = param.args.getOrNull(0) ?: return
                    if (alertsTabClass.isInstance(menuItem)) {
                        runCatching {
                            XposedHelpers.callMethod(param.thisObject, "g", menuItem, 0)
                        }.onFailure {
                            XposedBridge.log("[$TAG] Clear alerts badge failed: ${it.message}")
                        }
                    }
                }
            }

        XposedBridge.hookAllMethods(stateManagerClass, "c", clearBadgeHook)
        XposedBridge.hookAllMethods(stateManagerClass, "e", clearBadgeHook)
        XposedBridge.log("[$TAG] Alerts badge auto-clear installed")
    }
}
