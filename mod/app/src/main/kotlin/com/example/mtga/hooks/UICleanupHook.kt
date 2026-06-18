package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetResolver
import com.example.mtga.config.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Remove unwanted UI elements:
 *  1. "For You" tab: filter feeds with id="for_you"/"recommended" out of FeedsRepositoryImpl
 *  2. "Help Center" sidebar item: skip its sidebar-item Compose call
 *  3. Truth Gems: remove the gem badge (NavDrawerAvatar) + banner card (account drawer)
 *  4. "TRUTH+" top app bar button
 *  5. "Truth AI" bottom bar tab
 *  6. "Truth Search AI": disable the use case + blank the label
 *  7. Alert swipe-to-delete: disable the gesture on the alerts screen
 */
class UICleanupHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "UICleanup"

    override fun hook(classLoader: ClassLoader) {
        // Each sub-hook is isolated so one ClassNotFound (e.g. from a
        // missed R8 rename) doesn't strand the rest of the pipeline.
        runSubHook("HideForYou", SettingKeys.HideForYou, classLoader, ::hookForYouTab)
        runSubHook("HideHelpCenter", SettingKeys.HideHelpCenter, classLoader, ::hookHelpCenter)
        runSubHook("HideTruthGems", SettingKeys.HideTruthGems, classLoader, ::hookTruthGems)
        runSubHook("HideTruthPlus", SettingKeys.HideTruthPlus, classLoader, ::hookTruthPlusButton)
        runSubHook("HideAiTab", SettingKeys.HideAiTab, classLoader, ::hookBottomBarAiTab)
        runSubHook("DisableSearchAi", SettingKeys.DisableSearchAi, classLoader, ::hookSearchAI)
        runSubHook("DisableAlertSwipe", SettingKeys.DisableAlertSwipe, classLoader, ::hookDismissAlert)
        runSubHook("BlockTruthPlusUpsell", SettingKeys.BlockTruthPlusUpsell, classLoader, ::hookBlockTruthPlusUpsell)
        runSubHook("HideTopBannerAd", SettingKeys.HideTopBannerAd, classLoader, ::hookEmbeddedAnnouncement)
        runSubHook("HideLiveCarousel", SettingKeys.HideLiveCarousel, classLoader, ::hookLiveCarousel)
        // Compose-safe data-layer suppression of ad/announcement/live feed
        // items on calibrated builds (TargetSet.feedItemMapper). Supersedes the
        // two Compose no-op sub-hooks above, which desync the slot table.
        runCatching { installFeedItemFilter(classLoader) }
            .onFailure { XposedBridge.log("[$TAG] FeedItem filter failed: ${it.message}") }
    }

    /**
     * Compose-SAFE replacement for [hookEmbeddedAnnouncement] / [hookLiveCarousel]
     * on builds with a calibrated [TargetSet.feedItemMapper]: drop ad /
     * announcement / live items from the home-timeline list before the
     * dispatcher renders them, so no Composable is ever skipped. Skipping one
     * (the legacy `noopAllComposables` path) desyncs the Compose slot table and
     * freezes recomposition of neighbouring posts — e.g. the Like / ReTruth
     * buttons don't update until restart.
     *
     * Honours the two toggles independently: HideTopBannerAd drops the ad and
     * announcement item types; HideLiveCarousel drops the live ones.
     */
    private fun installFeedItemFilter(classLoader: ClassLoader) {
        val mapper = targets.feedItemMapper ?: return
        val drop = HashSet<String>()
        if (Settings.isOn(SettingKeys.HideTopBannerAd)) drop += AD_FEED_ITEM_TYPES
        if (Settings.isOn(SettingKeys.HideLiveCarousel)) drop += LIVE_FEED_ITEM_TYPES
        if (drop.isEmpty()) return

        val mapperClass = XposedHelpers.findClass(mapper.name, classLoader)
        val typeField = targets.feedItemTypeField
        XposedBridge.hookAllMethods(
            mapperClass,
            targets.feedItemMapperMethod,
            object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<Any> ?: return
                    val filtered =
                        list.filterNot { item ->
                            val type = runCatching { XposedHelpers.getObjectField(item, typeField) }.getOrNull()
                            val name = (type as? Enum<*>)?.name
                            name != null && name in drop
                        }
                    if (filtered.size != list.size) {
                        XposedBridge.log("[$TAG] FeedItem filter: dropped ${list.size - filtered.size}/${list.size} ($drop)")
                        param.result = ArrayList(filtered)
                    }
                }
            },
        )
        XposedBridge.log("[$TAG] Home FeedItem filter installed (${mapper.name}.${targets.feedItemMapperMethod}, drop=$drop)")
    }

    private fun runSubHook(
        label: String,
        key: String,
        classLoader: ClassLoader,
        action: (ClassLoader) -> Unit,
    ) {
        if (!Settings.isOn(key)) return
        try {
            action(classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] $label sub-hook failed: ${t.message}")
        }
    }

    /**
     * Hide the home-feed top sponsored banner. Three distinct renderers
     * ride this toggle:
     *  - [TargetSet.embeddedAnnouncement] — detail-screen path only.
     *  - [TargetSet.nonNativeAdRenderer] — `FeedItemType.NonNativeAd`
     *    items in the home feed.
     *  - [TargetSet.homeAnnouncementRenderer] — the actual UFC banner
     *    renderer dispatched as `FeedItemType.AnnouncementItem`.
     */
    private fun hookEmbeddedAnnouncement(classLoader: ClassLoader) {
        // On builds with a calibrated FeedItem mapper, ad/announcement banners
        // are dropped at the data layer ([installFeedItemFilter]); skipping the
        // renderer Composables here would desync the Compose slot table.
        if (targets.feedItemMapper != null) return
        targets.embeddedAnnouncement?.let { target ->
            val cls =
                try {
                    XposedHelpers.findClass(target.name, classLoader)
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] EmbeddedAnnouncement class missing (${target.name}): ${t.message}")
                    null
                }
            if (cls != null) {
                val killed = noopAllComposables(cls)
                XposedBridge.log("[$TAG] EmbeddedAnnouncementCard suppressed (${target.name}, $killed methods)")
            }
        }
        targets.nonNativeAdRenderer?.let { target ->
            val cls =
                try {
                    XposedHelpers.findClass(target.name, classLoader)
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] AdView class missing (${target.name}): ${t.message}")
                    null
                }
            if (cls != null) {
                val killed = noopAllComposables(cls)
                XposedBridge.log("[$TAG] AdView (NonNativeAd) suppressed (${target.name}, $killed methods)")
            }
        }
        targets.homeAnnouncementRenderer?.let { target ->
            val cls =
                try {
                    XposedHelpers.findClass(target.name, classLoader)
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] Announcement (home) class missing (${target.name}): ${t.message}")
                    null
                }
            if (cls != null) {
                val killed = noopAllComposables(cls)
                XposedBridge.log("[$TAG] Announcement (home feed) suppressed (${target.name}, $killed methods)")
            }
        }
    }

    /**
     * Hide the livestream/shows carousel (e.g. the "… Live" cards at the top of
     * the home feed). SAFE suppression: do NOT skip the carousel Composable —
     * skipping a Composable desyncs the Compose slot table and froze Like/
     * ReTruth recomposition (the original bug). Instead empty its data list in
     * `beforeHookedMethod`: the Composable still runs its full group machinery
     * and renders nothing via its own `isEmpty()` branch. This works whether
     * the carousel is a home-feed header or an in-timeline FeedItem, since it
     * targets the renderer itself rather than the feed list.
     */
    private fun hookLiveCarousel(classLoader: ClassLoader) {
        val emptyListArg =
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    for (i in param.args.indices) {
                        if (param.args[i] is List<*>) {
                            param.args[i] = emptyList<Any>()
                            return
                        }
                    }
                }
            }

        val target = targets.liveContentCarousel ?: return
        runCatching {
            val cls = XposedHelpers.findClass(target.name, classLoader)
            val n = XposedBridge.hookAllMethods(cls, targets.liveContentCarouselMethod, emptyListArg).size
            XposedBridge.log("[$TAG] Live carousel emptied (${target.name}.${targets.liveContentCarouselMethod}, $n method(s))")
        }.onFailure { XposedBridge.log("[$TAG] Live carousel hook failed (${target.name}): ${it.message}") }

        // Extra live renderers (e.g. the avatar chip strip with its
        // "See Less Often" section header): these are home-screen HEADERS,
        // not in-timeline FeedItems (rendered via A6.t, not the La.N$b
        // dispatcher). Emptying their list leaves the section header behind, so
        // suppress the whole Composable. Skipping a header doesn't desync the
        // timeline's LazyColumn, so Like/ReTruth recomposition stays intact.
        for (extra in targets.extraLiveRenderers) {
            runCatching {
                val extraCls = XposedHelpers.findClass(extra.name, classLoader)
                val killed = noopAllComposables(extraCls)
                XposedBridge.log("[$TAG] extra live renderer suppressed (${extra.name}, $killed methods)")
            }.onFailure { XposedBridge.log("[$TAG] extra live hook failed (${extra.name}): ${it.message}") }
        }
    }

    /**
     * Hook every method on [cls] whose shape matches a public Composable:
     * returns Unit and takes an `androidx.compose.runtime.Composer` as one
     * of its parameters. Picks up overloaded entry points and the synthetic
     * continuation variants Compose generates without us having to track
     * letter-by-letter names per build.
     */
    private fun noopAllComposables(cls: Class<*>): Int {
        var count = 0
        for (m in cls.declaredMethods) {
            if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
            if (m.returnType != Void.TYPE) continue
            val hasComposer =
                m.parameterTypes.any { p ->
                    p.name.startsWith("androidx.compose.runtime.") ||
                        p.name.matches(COMPOSER_PACKAGE_REGEX)
                }
            if (!hasComposer) continue
            runCatching {
                XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                count++
            }
        }
        return count
    }

    /**
     * Block navigation to either Truth+ upsell screen:
     *   Wb.M$a (truth-plus-modal-bottom-sheet): generic upsell sheet
     *   Wb.A$a (premium-feature-roadblock-dialog/{feature}): per-feature
     *      dialog with "This feature is available with Truth+"
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
     * The For You tab isn't gated by Features.forYouEnabled (that field is
     * unused). Home tabs are built from the Feed list returned by
     * GET /api/v2/feeds; a Feed with id="for_you" or "recommended" produces
     * the For You tab. Filter the list inside FeedsRepositoryImpl.
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
        return first.javaClass.name == resolver.resolveFeedClass()
    }

    private fun keepFeed(feed: Any): Boolean {
        val id = getFeedId(feed)
        val keep = id != "for_you" && id != "recommended"
        if (!keep) XposedBridge.log("[$TAG] Filtered feed id=$id")
        return keep
    }

    /**
     * Read `Feed.id`. ≤1.26.1 exposes the [TargetSet.feedIdMethod] getter
     * (`i()`); 1.26.2+ dropped it, so fall back to the [TargetSet.feedIdField]
     * (`a`) field. Both are sourced from the resolved TargetSet.
     */
    private fun getFeedId(feed: Any): String? {
        targets.feedIdMethod?.let { method ->
            runCatching { XposedHelpers.callMethod(feed, method)?.toString() }
                .getOrNull()
                ?.let { return it }
        }
        return runCatching { XposedHelpers.getObjectField(feed, targets.feedIdField)?.toString() }.getOrNull()
    }

    /**
     * Sidebar entries render via a Composable on [TargetSet.sidebarItemRenderer].
     * v1.26.1: a single `j(modifier, icon, textResId, hasDivider, onClick, …)`.
     * v1.26.2+: split into `m(modifier, iconId, textResId, hasDivider, onClick, …)`
     * and `n(modifier, vectorIcon, textResId, hasDivider, onClick, …)`. On
     * v1.27.1 the NavigationItem class drifted to `A6.l`, separate from the
     * `A6.I` TruthNavDrawer host.
     *
     * textResId's index differs across methods, so we scan all int args for
     * a match against [TargetSet.resStringHelpCenter].
     */
    private fun hookHelpCenter(classLoader: ClassLoader) {
        val sidebarItemClass = XposedHelpers.findClass(targets.sidebarItemRenderer.name, classLoader)
        val helpCenterId = resolver.resolveStringResId("help_center", targets.resStringHelpCenter)
        val skipIfHelpCenter =
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    for (arg in param.args) {
                        if (arg is Int && arg == helpCenterId) {
                            param.result = null
                            return
                        }
                    }
                }
            }
        for (methodName in targets.sidebarItemMethods) {
            XposedBridge.hookAllMethods(sidebarItemClass, methodName, skipIfHelpCenter)
        }
        XposedBridge.log("[$TAG] Help Center sidebar item suppressed (methods=${targets.sidebarItemMethods})")
    }

    /**
     * Truth Gems renders from:
     *  - [TargetSet.navDrawerAvatar]: default-zero badge (grey gem) and
     *    count badge (blue gem). Methods in
     *    [TargetSet.navDrawerAvatarBadgeMethods].
     *  - [TargetSet.accountDrawerScreen]: drawer-header gem button +
     *    Truth Gems banner card. Methods in
     *    [TargetSet.accountDrawerGemMethods].
     */
    private fun hookTruthGems(classLoader: ClassLoader) {
        val navAvatar = XposedHelpers.findClass(targets.navDrawerAvatar.name, classLoader)
        for (methodName in targets.navDrawerAvatarBadgeMethods) {
            XposedBridge.hookAllMethods(navAvatar, methodName, XC_MethodReplacement.DO_NOTHING)
        }

        val drawer = XposedHelpers.findClass(targets.accountDrawerScreen.name, classLoader)
        for (methodName in targets.accountDrawerGemMethods) {
            XposedBridge.hookAllMethods(drawer, methodName, XC_MethodReplacement.DO_NOTHING)
        }

        XposedBridge.log(
            "[$TAG] Truth Gems suppressed (avatar=${targets.navDrawerAvatarBadgeMethods}, " +
                "drawer=${targets.accountDrawerGemMethods})",
        )
    }

    /** The TRUTH+ upsell button in the top app bar (Composable lambda). */
    private fun hookTruthPlusButton(classLoader: ClassLoader) {
        val topBarClass = XposedHelpers.findClass(targets.topAppBarFactory.name, classLoader)
        XposedBridge.hookAllMethods(topBarClass, targets.topAppBarTruthPlusMethod, XC_MethodReplacement.DO_NOTHING)
        XposedBridge.log("[$TAG] Truth+ top bar button suppressed (${targets.topAppBarTruthPlusMethod})")
    }

    /**
     * Bottom navigation tab list.
     *  - v1.26.1: filter the AI tab subclass out of the `List<Tab>` returned
     *    by [TargetSet.bottomNavTabsListMethod].
     *  - v1.26.2+: AI tab is gone and the tab list lives in static fields,
     *    so the hook silently no-ops when [TargetSet.bottomNavAiTab] is null.
     */
    private fun hookBottomBarAiTab(classLoader: ClassLoader) {
        val aiTabTarget = targets.bottomNavAiTab
        val listMethod = targets.bottomNavTabsListMethod
        if (aiTabTarget == null || listMethod == null) {
            XposedBridge.log("[$TAG] Bottom bar AI tab hook skipped — not present on this build")
            return
        }
        val tabsClass = XposedHelpers.findClass(targets.bottomNavTabs.name, classLoader)
        val aiTabClass =
            try {
                XposedHelpers.findClass(aiTabTarget.name, classLoader)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] AI tab class missing (${aiTabTarget.name}): ${t.message}")
                return
            }
        XposedBridge.hookAllMethods(
            tabsClass,
            listMethod,
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
        // v1.24.8 / v1.26.1: a single `SearchAIUseCase` (FQN-stable on
        // v1.24 via Hilt) with method `invoke`. Hook → no-op.
        // v1.26.2 / v1.27.0: the monolithic use case is gone; v1.27.0's
        // `g8.l` is an empty class. The AI query lives on `Q7.b`
        // (AIRepositoryImpl); the suspend `a(String query, c)` returns a
        // `Flow<qc.d>`. No-op every non-getter `Object`-returning method on
        // `Q7.b` so submitting a query produces nothing (`g8.o`/`g8.h`/`g8.i`
        // all delegate to `Q7.b`).
        val legacyHooked = tryHookLegacySearchUseCase(classLoader)
        val repoHooked = tryHookAiRepository(classLoader)
        if (!legacyHooked && !repoHooked) {
            XposedBridge.log("[$TAG] SearchAI: no use case or repository found; relying on label blank")
        }
        blankStringResource(classLoader, "Truth Search AI")
        blankStringResource(classLoader, "Ask Perplexity AI")
        hookAskPerplexityButton(classLoader)
    }

    /**
     * Hide the "Ask Perplexity AI" Composable above the Discover feed (and
     * elsewhere in v1.27.0). Replacing the renderer with a no-op makes the
     * button cell collapse to zero size; the surrounding column pulls
     * subsequent content up.
     */
    private fun hookAskPerplexityButton(classLoader: ClassLoader) {
        val target = targets.askPerplexityButton ?: return
        val cls =
            try {
                XposedHelpers.findClass(target.name, classLoader)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] SearchAI: askPerplexityButton class missing (${target.name}): ${t.message}")
                return
            }
        XposedBridge.hookAllMethods(cls, targets.askPerplexityButtonMethod, XC_MethodReplacement.DO_NOTHING)
        XposedBridge.log(
            "[$TAG] SearchAI: Ask Perplexity AI button suppressed (${target.name}.${targets.askPerplexityButtonMethod})",
        )
    }

    private fun tryHookLegacySearchUseCase(classLoader: ClassLoader): Boolean {
        val candidates =
            sequenceOf(
                "com.truthsocial.app.domain.usecase.ai.SearchAIUseCase",
                targets.searchAiUseCase.name,
            )
        for (className in candidates.distinct()) {
            try {
                val searchClass = XposedHelpers.findClass(className, classLoader)
                val invokes = searchClass.declaredMethods.filter { it.name == "invoke" }
                if (invokes.isEmpty()) continue
                invokes.forEach { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
                XposedBridge.log("[$TAG] SearchAI use case disabled ($className)")
                return true
            } catch (_: Throwable) {
                // try next candidate
            }
        }
        return false
    }

    private fun tryHookAiRepository(classLoader: ClassLoader): Boolean {
        // Locate the AI repository impl (v1.27.0: `Q7.b`) via sibling probe.
        val repoClass = discoverAiRepositoryImpl(classLoader) ?: return false
        // Neutralise only the actual search-query method (a suspend function
        // shaped `Object a(String query, Continuation c)` returning a Flow).
        // Other methods on the repo (current-query getter, cached-result
        // lookup, error stream) are left alone. Return
        // `kotlinx.coroutines.flow.emptyFlow()` so callers iterate over zero
        // results instead of crashing on null.
        val emptyFlow =
            runCatching {
                val flowKt = classLoader.loadClass("kotlinx.coroutines.flow.FlowKt")
                flowKt.getDeclaredMethod("emptyFlow").invoke(null)
            }.getOrNull()
        var count = 0
        for (m in repoClass.declaredMethods) {
            if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
            // Suspend search method: (String, Continuation) → Object.
            // R8 renames `kotlin.coroutines.Continuation` to a short class
            // (v1.27.0: `te.c`, a single-method interface with
            // `resumeWith(Object)`). Recognise structurally rather than by
            // name so the hook survives further renames.
            if (m.parameterCount != 2) continue
            val (p0, p1) = m.parameterTypes
            if (p0 != String::class.java) continue
            if (!isContinuationLike(p1)) continue
            if (m.returnType != java.lang.Object::class.java) continue
            try {
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? = emptyFlow
                    },
                )
                count++
            } catch (_: Throwable) {
                // skip
            }
        }
        if (count == 0) return false
        XposedBridge.log("[$TAG] SearchAI repository search neutralised (${repoClass.name}, $count methods)")
        return true
    }

    /**
     * Heuristic match for `kotlin.coroutines.Continuation` after R8 rename.
     * The interface declares two abstract methods (`getContext` and
     * `resumeWith(Object)`); checking the latter is enough.
     */
    private fun isContinuationLike(cls: Class<*>): Boolean {
        if (!cls.isInterface) return false
        return cls.methods.any { it.name == "resumeWith" && it.parameterCount == 1 } ||
            // R8 may rename even `resumeWith` on the interface; fall back
            // to checking package prefix `te.` (Kotlin stdlib runtime).
            cls.name.startsWith("te.") ||
            cls.name == "kotlin.coroutines.Continuation"
    }

    /**
     * Locate the AI repository impl by walking single-letter siblings of
     * the interface package `Q7.a` (R8 convention: impl is one letter
     * further in the same package; v1.27.0 has `Q7.b` for `Q7.a`). The
     * interface FQN isn't fully stable but `AISearchResult` returned from
     * one of its methods is, so verify the candidate's declared methods
     * reference the AI result data class.
     */
    private fun discoverAiRepositoryImpl(classLoader: ClassLoader): Class<*>? {
        // Static guess first (Q7.b for v1.27.0) via the interface.
        for (interfaceFqn in listOf("Q7.a")) {
            for (suffix in 'b'..'z') {
                val candidate = interfaceFqn.substringBeforeLast('.') + ".$suffix"
                val cls = runCatching { classLoader.loadClass(candidate) }.getOrNull() ?: continue
                val refsAiResult =
                    cls.declaredMethods.any { m ->
                        m.returnType.name.endsWith(".AISearchResult") ||
                            m.parameterTypes.any { it.name.endsWith(".AISearchResult") }
                    }
                if (refsAiResult) return cls
            }
        }
        return null
    }

    /**
     * Disable the swipe-to-delete gesture on the Alerts screen.
     *
     * SwipeableRow.j(modifier, swipeToStartAction, swipeToEndAction, state,
     * content, …) is shared by many screens, so we only neutralise the call
     * when it originates from R8.D / R8.Y (AlertsScreen / AlertsViewModel)
     * by inspecting the current thread's stack.
     */
    private fun hookDismissAlert(classLoader: ClassLoader) {
        val swipeRowClass = XposedHelpers.findClass(targets.swipeableRow.name, classLoader)
        XposedBridge.hookAllMethods(
            swipeRowClass,
            targets.swipeableRowMethod,
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
        val prefix = targets.alertsScreenPackagePrefix
        for (frame in stack) {
            // AlertsScreen Composables live in a per-build R8-renamed
            // package: `R8.` on v1.24.8/v1.26.1, `A8.` on v1.26.2, `B8.` on
            // v1.27.0 (see [TargetSet.alertsScreenPackagePrefix]).
            if (frame.className.startsWith(prefix)) return true
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

    private companion object {
        // R8 collapses `androidx.compose.runtime.*` into a single
        // `<letter>0` top-level package per release (drifts between
        // builds: `s0` on v1.24.10, `v0` on v1.27.x).
        private val COMPOSER_PACKAGE_REGEX = Regex("""^[a-z]0\..+""")

        // FeedItemType enum constant names dropped by [installFeedItemFilter].
        // The enum is FQN-stable (Moshi-serialised), so `Enum.name()` is stable
        // across R8 builds. HideTopBannerAd drops ads + the home announcement
        // banner; HideLiveCarousel drops the livestream carousel + channel guide.
        private val AD_FEED_ITEM_TYPES = setOf("NonNativeAd", "NativeAd", "Announcement")
        private val LIVE_FEED_ITEM_TYPES = setOf("LiveShowsCarousel", "ChannelGuideItem")
    }
}
