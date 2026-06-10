package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Fix the bottom-bar notification badge not refreshing after the user views
 * their alerts.
 *
 * Truth Social's AlertsApi has no "mark all as read" endpoint. The badge
 * count comes from `alertsRepository.getAlertsBadgeCount()`, which mirrors
 * the server-side unread count. After viewing alerts the local app never
 * clears it; the bell keeps showing the count until something else updates
 * the StateFlow.
 *
 * Workaround: hook every method on AppStateManagerImpl called with a `Tab`
 * argument when the user/framework selects a tab (R8-assigned letters drift
 * between builds; see [TargetSet.appStateTabSelectMethods]). When the
 * selected tab is the Alerts tab, invoke [TargetSet.appStateClearBadgeMethod]
 * with `(tab, 0)` to zero the badge.
 *
 * Only the badge bubble is hidden; alert items remain visible.
 */
class NotificationBadgeFixHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "NotificationBadgeFix"

    override fun hook(classLoader: ClassLoader) {
        val stateManagerClass = XposedHelpers.findClass(targets.appStateManager.name, classLoader)
        val alertsTabClass = XposedHelpers.findClass(targets.bottomNavAlertsTab.name, classLoader)
        val clearBadgeMethod = targets.appStateClearBadgeMethod

        val clearBadgeHook =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val menuItem = param.args.getOrNull(0) ?: return
                    if (alertsTabClass.isInstance(menuItem)) {
                        runCatching {
                            XposedHelpers.callMethod(param.thisObject, clearBadgeMethod, menuItem, 0)
                        }.onFailure {
                            XposedBridge.log("[$TAG] Clear alerts badge failed: ${it.message}")
                        }
                    }
                }
            }

        for (methodName in targets.appStateTabSelectMethods) {
            XposedBridge.hookAllMethods(stateManagerClass, methodName, clearBadgeHook)
        }
        XposedBridge.log(
            "[$TAG] Alerts badge auto-clear installed " +
                "(tabSelect=${targets.appStateTabSelectMethods}, clearBadge=$clearBadgeMethod)",
        )
    }
}
