package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetSet
import com.example.mtga.config.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Removes unwanted UI elements:
 *  1. "For You" tab — filter feeds with id="for_you"/"recommended" out of FeedsRepositoryImpl
 *  2. "Help Center" sidebar item — skip its sidebar-item Compose call
 *  3. Truth Gems — remove the gem badge (NavDrawerAvatar) + the banner card (account drawer)
 *  4. "TRUTH+" top app bar button
 *  5. "Truth AI" bottom bar tab
 *  6. "Truth Search AI" — disable the use case + blank the label
 *  7. Alert swipe-to-delete — disable the gesture on the alerts screen
 */
class UICleanupHook(
    targets: TargetSet,
) : BaseHook(targets) {
    override val name = "UICleanup"

    override fun hook(classLoader: ClassLoader) {
        if (Settings.isOn(SettingKeys.HideForYou)) hookForYouTab(classLoader)
        if (Settings.isOn(SettingKeys.HideHelpCenter)) hookHelpCenter(classLoader)
        if (Settings.isOn(SettingKeys.HideTruthGems)) hookTruthGems(classLoader)
        if (Settings.isOn(SettingKeys.HideTruthPlus)) hookTruthPlusButton(classLoader)
        if (Settings.isOn(SettingKeys.HideAiTab)) hookBottomBarAiTab(classLoader)
        if (Settings.isOn(SettingKeys.DisableSearchAi)) hookSearchAI(classLoader)
        if (Settings.isOn(SettingKeys.DisableAlertSwipe)) hookDismissAlert(classLoader)
        if (Settings.isOn(SettingKeys.BlockTruthPlusUpsell)) hookBlockTruthPlusUpsell(classLoader)
    }

    /**
     * Block navigation to either Truth+ upsell screen:
     *   Wb.M$a (truth-plus-modal-bottom-sheet)            — generic upsell sheet
     *   Wb.A$a (premium-feature-roadblock-dialog/{feature}) — per-feature dialog
     *                                                        with "This feature is
     *                                                        available with Truth+"
     */
    private fun hookBlockTruthPlusUpsell(classLoader: ClassLoader) {
        val navHandlerClass = XposedHelpers.findClass(targets.navHandler.name, classLoader)
        val blockedRouteClasses =
            listOf(
                XposedHelpers.findClass(targets.truthPlusUpsellRoute.name, classLoader),
                XposedHelpers.findClass(targets.premiumFeatureRoadblockRoute.name, classLoader),
            )
        XposedBridge.hookAllMethods(
            navHandlerClass,
            targets.navHandlerNavigateMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val route = param.args.getOrNull(0) ?: return
                    if (blockedRouteClasses.any { it.isInstance(route) }) {
                        XposedBridge.log("[$TAG] Blocked Truth+ upsell navigation: ${route.javaClass.name}")
                        param.result = null
                    }
                }
            },
        )
        XposedBridge.log("[$TAG] Truth+ upsell blocker installed (modal + roadblock)")
    }

    /**
     * The For You tab is not gated by Features.forYouEnabled (that field is
     * unused). The home tabs are constructed from the Feed list returned by
     * GET /api/v2/feeds; a Feed with id="for_you" or id="recommended"
     * produces the For You tab. We filter the list inside
     * FeedsRepositoryImpl.
     */
    private fun hookForYouTab(classLoader: ClassLoader) {
        val repoClass = XposedHelpers.findClass(targets.feedsRepository.name, classLoader)
        var hookCount = 0
        for (method in repoClass.declaredMethods) {
            val takesList = method.parameterTypes.any { it == List::class.java }
            val returnsList = method.returnType == List::class.java
            if (returnsList) {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) = filterFeedListResult(param)
                    },
                )
                hookCount++
            }
            if (takesList) {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) = filterFeedListArgs(param)
                    },
                )
                hookCount++
            }
        }
        XposedBridge.log("[$TAG] FeedsRepository hooks installed: $hookCount methods")
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterFeedListResult(param: XC_MethodHook.MethodHookParam) {
        val list = param.result as? List<Any> ?: return
        if (!isFeedList(list)) return
        val filtered = list.filter { keepFeed(it) }
        if (filtered.size != list.size) param.result = filtered
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterFeedListArgs(param: XC_MethodHook.MethodHookParam) {
        for (i in param.args.indices) {
            val list = param.args[i] as? List<Any> ?: continue
            if (!isFeedList(list)) continue
            val filtered = list.filter { keepFeed(it) }
            if (filtered.size != list.size) param.args[i] = filtered
        }
    }

    private fun isFeedList(list: List<*>): Boolean {
        val first = list.firstOrNull() ?: return false
        return first.javaClass.name == FEED_CLASS
    }

    private fun keepFeed(feed: Any): Boolean {
        val id = getFeedId(feed)
        val keep = id != "for_you" && id != "recommended"
        if (!keep) XposedBridge.log("[$TAG] Filtered feed id=$id")
        return keep
    }

    /** Feed.getId() is renamed to i() and field 'id' to 'a' by R8. */
    private fun getFeedId(feed: Any): String? =
        try {
            XposedHelpers.callMethod(feed, "i")?.toString()
        } catch (_: Throwable) {
            try {
                XposedHelpers.getObjectField(feed, "a")?.toString()
            } catch (_: Throwable) {
                null
            }
        }

    /**
     * Each sidebar entry is rendered by sidebarItemRenderer.j(modifier, icon,
     * textResId, hasDivider, onClick, …). Skip when textResId == help_center.
     */
    private fun hookHelpCenter(classLoader: ClassLoader) {
        val sidebarItemClass = XposedHelpers.findClass(targets.sidebarItemRenderer.name, classLoader)
        XposedBridge.hookAllMethods(
            sidebarItemClass,
            "j",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val textResId = param.args.getOrNull(2) as? Int ?: return
                    if (textResId == targets.resStringHelpCenter) {
                        param.result = null
                    }
                }
            },
        )
        XposedBridge.log("[$TAG] Help Center sidebar item suppressed")
    }

    /**
     * Truth Gems is rendered by:
     *  - NavDrawerAvatar.k() — default-zero badge (grey gem)
     *  - NavDrawerAvatar.m(user) — actual-count badge (blue gem)
     *  - AccountDrawerScreen.M() — drawer-header gem button
     *  - AccountDrawerScreen.b0() — Truth Gems banner card in the drawer
     */
    private fun hookTruthGems(classLoader: ClassLoader) {
        val navAvatar = XposedHelpers.findClass(targets.navDrawerAvatar.name, classLoader)
        XposedBridge.hookAllMethods(navAvatar, "k", XC_MethodReplacement.DO_NOTHING)
        XposedBridge.hookAllMethods(navAvatar, "m", XC_MethodReplacement.DO_NOTHING)

        val drawer = XposedHelpers.findClass(targets.accountDrawerScreen.name, classLoader)
        XposedBridge.hookAllMethods(drawer, "M", XC_MethodReplacement.DO_NOTHING)
        XposedBridge.hookAllMethods(drawer, "b0", XC_MethodReplacement.DO_NOTHING)

        XposedBridge.log("[$TAG] Truth Gems suppressed")
    }

    /**
     * The TRUTH+ upsell button in the top app bar — Composable lambda i().
     */
    private fun hookTruthPlusButton(classLoader: ClassLoader) {
        val topBarClass = XposedHelpers.findClass(targets.topAppBarFactory.name, classLoader)
        XposedBridge.hookAllMethods(topBarClass, "i", XC_MethodReplacement.DO_NOTHING)
        XposedBridge.log("[$TAG] Truth+ top bar button suppressed")
    }

    /**
     * Bottom navigation tab list comes from BottomNavTabs.a(). Filter out the
     * AI tab subclass instance.
     */
    private fun hookBottomBarAiTab(classLoader: ClassLoader) {
        val tabsClass = XposedHelpers.findClass(targets.bottomNavTabs.name, classLoader)
        val aiTabClass = XposedHelpers.findClass(targets.bottomNavAiTab.name, classLoader)
        XposedBridge.hookAllMethods(
            tabsClass,
            "a",
            object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<Any> ?: return
                    val filtered = list.filterNot { aiTabClass.isInstance(it) }
                    if (filtered.size != list.size) param.result = filtered
                }
            },
        )
        XposedBridge.log("[$TAG] Bottom bar AI tab suppressed")
    }

    private fun hookSearchAI(classLoader: ClassLoader) {
        try {
            val searchClass =
                XposedHelpers.findClass(
                    "com.truthsocial.app.domain.usecase.ai.SearchAIUseCase",
                    classLoader,
                )
            XposedBridge.hookAllMethods(searchClass, "invoke", XC_MethodReplacement.DO_NOTHING)
            XposedBridge.log("[$TAG] SearchAI use case disabled")
        } catch (_: Throwable) {
            // Use case may have been removed in newer versions; the label hook below still runs.
        }
        blankStringResource(classLoader, "Truth Search AI")
    }

    /**
     * Disable the swipe-to-delete gesture on the Alerts screen.
     *
     * SwipeableRow.j(modifier, swipeToStartAction, swipeToEndAction, state,
     * content, …) is shared by many screens, so we only neutralize the call
     * when it originates from R8.D / R8.Y (AlertsScreen / AlertsViewModel)
     * by inspecting the current thread's stack.
     */
    private fun hookDismissAlert(classLoader: ClassLoader) {
        val swipeRowClass = XposedHelpers.findClass(targets.swipeableRow.name, classLoader)
        XposedBridge.hookAllMethods(
            swipeRowClass,
            "j",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isCalledFromAlertsScreen()) return
                    param.args[1] = null // swipeToStartAction
                    param.args[2] = null // swipeToEndAction
                }
            },
        )
        XposedBridge.log("[$TAG] Alerts swipe-to-delete disabled")
    }

    private fun isCalledFromAlertsScreen(): Boolean {
        val stack = Thread.currentThread().stackTrace
        for (frame in stack) {
            val name = frame.className
            // R8.D = AlertsScreenKt, R8.Y = AlertsViewModel — both are the
            // alerts screen's own package in v1.24.8. If a future build moves
            // them this filter will silently stop firing; that is the safe
            // failure mode (other swipes keep working).
            if (name.startsWith("R8.")) return true
        }
        return false
    }

    private fun blankStringResource(
        classLoader: ClassLoader,
        target: String,
    ) {
        XposedHelpers.findAndHookMethod(
            "android.content.res.Resources",
            classLoader,
            "getText",
            Int::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result?.toString() == target) param.result = ""
                }
            },
        )
    }

    companion object {
        private const val FEED_CLASS = "com.truthsocial.app.data.models.feeds.Feed"
    }
}
