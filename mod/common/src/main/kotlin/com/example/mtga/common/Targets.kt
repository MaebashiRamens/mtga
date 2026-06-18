package com.example.mtga.common

/**
 * Hook / patch coordinates for Truth Social, keyed by app version.
 *
 * R8 renames classes and methods on every build, so v1.24.8 names won't
 * match v1.25.0. One [TargetSet] per tested version. At hook init we look up
 * the current versionCode and pick the matching set; on a miss we fall back to
 * [latest], warn the user, and rely on each hook's own try/catch (and the
 * [FallbackResolver] dynamic-discovery path) to degrade gracefully rather than
 * crash the host.
 *
 * ## Calibration workflow (adding a new version)
 *
 * 1. Drop the apkmirror `.apkm` bundle into the project root.
 * 2. `unzip` it; record `sha256sum base.apk` (goes into [BuildId]).
 * 3. `nix develop --command jadx --no-res -d /tmp/jadx_<v> /tmp/<v>/base.apk`.
 *    [resStringHelpCenter] can be read with `aapt2 dump resources`; `--no-res`
 *    is fine for class discovery.
 * 4. For each [TargetSet] field, follow its `HOW TO LOCATE` note. Most fields
 *    stay identical between minor releases.
 *
 *    JADX file-name vs JVM class name: jadx renames class files when the
 *    original would collide on a case-insensitive filesystem (`e.java` vs
 *    `E.java`). The DEX/JVM class name is unchanged — read the
 *    `/* JADX INFO: renamed from: <pkg>.<name> */` marker near the top.
 *    Always feed the original DEX name into [ClassTarget], never jadx's
 *    display name (`C1744e`).
 *
 * 5. Verify each method-name field. R8 renames methods independently of
 *    their owning class.
 * 6. Append a new `TargetsV<X_Y_Z>` constant and register it in [knownVersions].
 * 7. `./gradlew :mod:app:assembleDebug` and `nix run .#build-patches`.
 */
object Targets {
    const val PACKAGE = "com.truthsocial.android.app"

    val knownVersions: List<TargetSet> =
        listOf(
            TargetsV1_27_1,
            TargetsV1_27_0,
            TargetsV1_26_2,
            TargetsV1_26_1,
            TargetsV1_24_10,
            TargetsV1_24_8,
            TargetsV1_24_6,
        )

    init {
        // The lookups below assume versionCode and versionName each identify at
        // most one TargetSet; a duplicate would make forVersionCode/forVersionName
        // silently shadow an entry. Catch a bad calibration at class-load time.
        val codes = knownVersions.map { it.buildId.versionCode }
        require(codes.toSet().size == codes.size) { "knownVersions has duplicate versionCode(s): $codes" }
        val names = knownVersions.map { it.buildId.versionName }
        require(names.toSet().size == names.size) { "knownVersions has duplicate versionName(s): $names" }
    }

    val knownVersionNames: Array<String>
        get() = knownVersions.map { it.buildId.versionName }.toTypedArray()

    fun forVersionCode(versionCode: Int): TargetSet? = knownVersions.firstOrNull { it.buildId.versionCode == versionCode }

    fun forVersionName(versionName: String): TargetSet? = knownVersions.firstOrNull { it.buildId.versionName == versionName }

    /**
     * The newest calibrated set, used as the fallback for unknown builds.
     * Derived from the max versionCode so it stays correct regardless of the
     * order entries are listed in [knownVersions] (step 6 of the calibration
     * workflow says "append", which a positional `first()` would get wrong).
     */
    val latest: TargetSet get() = knownVersions.maxByOrNull { it.buildId.versionCode }!!

    /**
     * Look up the TargetSet for a versionCode, falling back to [latest] when
     * the running build is unknown. [TargetMatch.exact] tells MainHook to use
     * [StaticResolver] (exact match) or [FallbackResolver] (dynamic discovery
     * for symbols with stable anchors, static fallback otherwise).
     */
    fun forVersionCodeOrLatest(versionCode: Int): TargetMatch {
        forVersionCode(versionCode)?.let { return TargetMatch(it, exact = true, warning = null) }
        val fallback = latest
        return TargetMatch(
            set = fallback,
            exact = false,
            warning =
                "Unknown versionCode=$versionCode — falling back to " +
                    "${fallback.buildId.versionName} (${fallback.buildId.versionCode}). " +
                    "Some hooks may misbehave.",
        )
    }
}

/** Result of [Targets.forVersionCodeOrLatest]. */
data class TargetMatch(
    val set: TargetSet,
    val exact: Boolean,
    val warning: String?,
)

/**
 * Maps "thing we want to hook" to "obfuscated name in this Truth Social build".
 * Each field carries a HOW TO LOCATE note so future calibration sessions can
 * re-find the symbol on a fresh APK without re-discovering anchor strings.
 *
 * Defaults are supplied for fields added when v1.26.2 support landed so
 * existing v1.24.x / v1.26.1 calibrations keep working.
 */
data class TargetSet(
    val buildId: BuildId,
    // ---------------------------- Network ------------------------------------
    /**
     * OkHttp interceptor that injects the Play Integrity assertion.
     *
     * HOW TO LOCATE: grep the literal `"x-tru-assertion"` (the header it
     * adds). One class hits; confirm by reading its `intercept(chain)` body.
     */
    val integrityInterceptor: ClassTarget,
    /** HOW TO LOCATE: the only method on [integrityInterceptor] returning Object/Response. R8 single-letter (typically `a`). */
    val integrityInterceptMethod: String,
    /**
     * Field on the OkHttp `RealInterceptorChain` impl holding the current Request.
     *
     * HOW TO LOCATE: in the chain class (`Be.h`-shape: list of interceptors +
     * int index), the Request field is the only one typed as the obfuscated
     * `okhttp3.Request`. R8 names by alphabetical position (5th field → `e`).
     */
    val chainRequestField: String,
    /** HOW TO LOCATE: in the chain class, the method whose body advances the interceptor index and returns a Response. Usually `b`. */
    val chainProceedMethod: String,
    /**
     * Concrete OkHttp `RealInterceptorChain` impl that [integrityInterceptor]
     * receives as its `Interceptor.Chain` argument. Only the bytecode integrity
     * bypass needs it (it check-casts the chain to this type to read
     * [chainRequestField] and call [chainProceedMethod]); the runtime hook does
     * the same reflectively, so it has no equivalent field.
     *
     * HOW TO LOCATE: the class declaring `${chainRequestField}` (typed as the
     * obfuscated okhttp Request) and `${chainProceedMethod}(Request)Response`.
     * Renamed Be.h (≤1.26.1) → og.g (1.26.2) → tg.g (1.27.0) → xg.g (1.27.1).
     */
    val integrityChain: ClassTarget,
    /**
     * `okhttp3.Request` after R8 — type of [chainRequestField] and the return
     * type of [retrofitOkHttpCallRequestMethod]. Used by the integrity bypass
     * and okhttp ad-block patches.
     *
     * HOW TO LOCATE: the declared type of the `${chainRequestField}` field on
     * [integrityChain].
     */
    val okhttpRequest: ClassTarget,
    /**
     * `okhttp3.Response` after R8 — return type of [chainProceedMethod] on
     * [integrityChain]. Used by the integrity bypass patch.
     *
     * HOW TO LOCATE: the return type of `${chainProceedMethod}(Request)` on
     * [integrityChain].
     */
    val okhttpResponse: ClassTarget,
    /**
     * Retrofit's `OkHttpCall`. Retrofit's ProGuard rules keep the original
     * FQN on every build. Hard-coded as a sanity check.
     */
    val retrofitOkHttpCall: ClassTarget,
    /**
     * R8-renamed `enqueue(Callback)` on [retrofitOkHttpCall]. Patched by
     * [BlockOkHttpAdsPatch] to short-circuit `/truth/ads` requests.
     *
     * HOW TO LOCATE: the only public void method on `OkHttpCall` taking a
     * single `retrofit2.Callback` argument (itself R8-renamed; look for the
     * parameter type whose single non-default method is named `onResponse`).
     * Usually single-letter `l`.
     */
    val retrofitOkHttpCallEnqueueMethod: String,
    /**
     * R8-renamed `createRawCall()` analog. Builds the `okhttp3.Request` from
     * `requestFactory.create(args)`. Returns `we.B` (Request).
     *
     * HOW TO LOCATE: the only public method on `OkHttpCall` whose return
     * type is the Request type (`Lwe/B;` shape, a class with a single
     * `<init>(Builder)`). Usually `declared-synchronized`, usually `p`.
     */
    val retrofitOkHttpCallRequestMethod: String,
    // ---------------------------- Repositories -------------------------------
    /**
     * `FeedsRepositoryImpl`. Methods that take or return `List<Feed>` where
     * `Feed = com.truthsocial.app.data.models.feeds.Feed`.
     *
     * HOW TO LOCATE: grep the `Feed` literal; narrow to a DI'd class with
     * several `List<Feed>` methods.
     */
    val feedsRepository: ClassTarget,
    /**
     * Getter for `Feed.id`, or `null` when the build exposes no getter and the
     * id must be read from [feedIdField] directly. On ≤1.26.1 R8 keeps a
     * `public final i()Ljava/lang/String;` getter; on 1.26.2+ the getter is
     * gone and [feedIdField] is public, so [HideForYouPatch] reads the field.
     *
     * HOW TO LOCATE: in the Feed class, the no-arg `String` method whose body
     * returns [feedIdField]. If none exists, set `null`.
     */
    val feedIdMethod: String?,
    /**
     * Backing field for `Feed.id`. Stable as `a` across every calibrated build
     * (first declared property). Private ≤1.26.1 (read via [feedIdMethod]),
     * public 1.26.2+ (read directly).
     */
    val feedIdField: String = "a",
    /**
     * Home-timeline FeedItem mapper: the plain `static` method that builds the
     * `List<FeedItem>` the timeline iterates from the merged status/ad/
     * announcement/live list. Hooking its returned list to drop ad/announcement/
     * live items is the Compose-SAFE way to suppress those banners — skipping
     * the renderer Composables (the legacy `noopAllComposables` path) desyncs
     * the Compose slot table and breaks recomposition of neighbouring posts.
     *
     * Null on builds not yet calibrated for the data-layer approach; those fall
     * back to the legacy Compose no-op hooks in [com.example.mtga.hooks.UICleanupHook].
     *
     * HOW TO LOCATE: the `public static` method returning `ArrayList` and taking
     * `(List, TimelineType)`; its result is handed to the TruthTimeline
     * composable. The list elements are a wrapper with a `FeedItemType` field
     * ([feedItemTypeField]). v1.27.1: `k0.m`.
     */
    val feedItemMapper: ClassTarget? = null,
    /** Method on [feedItemMapper] building the FeedItem list. Usually `f`. */
    val feedItemMapperMethod: String = "f",
    /**
     * Field on each FeedItem wrapper holding its `FeedItemType` enum. The hook
     * reads it reflectively and drops items by the enum's stable `name()`
     * (`NonNativeAd`/`Announcement`/`LiveShowsCarousel`/…). Usually `b`.
     */
    val feedItemTypeField: String = "b",
    /**
     * The FeedItem wrapper class held in the list [feedItemMapper] returns
     * (`ld.a` on v1.27.1, `ed.a` on v1.26.2, `hd.a` on v1.27.0). The LSPosed
     * hook reads [feedItemTypeField] reflectively and needs no descriptor, but
     * the build-time ReVanced filter has to `iget` the field, so it needs the
     * wrapper's DEX type. Null where [feedItemMapper] is null.
     */
    val feedItemWrapper: ClassTarget? = null,
    /**
     * `AppStateManagerImpl`. Bottom-bar nav + badge counts.
     *
     * HOW TO LOCATE: a class with `c(menuItem)`, `e(menuItem)` and
     * `g(menuItem, int)` where `g(_, 0)` clears the alerts badge.
     */
    val appStateManager: ClassTarget,
    /**
     * Methods on [appStateManager] receiving a `Tab` argument when the user
     * selects a bottom-bar tab. v1.26.1: `["c","e"]`. v1.26.2: `["b","j"]`.
     * v1.27.0: `["c","e"]`.
     */
    val appStateTabSelectMethods: List<String> = listOf("c", "e"),
    /**
     * Method on [appStateManager] taking `(Tab, int)`; clears the
     * notification badge when called with `(alertsTab, 0)`. v1.26.1: `"g"`.
     * v1.26.2: `"e"`. v1.27.0: `"d"`.
     */
    val appStateClearBadgeMethod: String = "g",
    // ---------------------------- Ads / analytics ----------------------------
    /**
     * `AdQueueManager`. Historically `b()` (fetchAd) returning Object and
     * `c(...)` (insertAdsIntoFeed) taking a feed list. v1.26.2+ dropped
     * `b()` and changed `c()` to a suspend function returning `List<ke.j>`;
     * the side-effecting writer is now `e()`.
     *
     * HOW TO LOCATE: grep `/api/v5/truth/ads`. AdQueueManager is DI'd from
     * the AdsApi consumer. Kotlin metadata names it
     * `com.truthsocial.app.data.api.service.ads.AdQueueManager`.
     */
    val adQueueManager: ClassTarget,
    /**
     * Optional fetch-ads method on [adQueueManager]. Null on v1.26.2+ where
     * `b()` no longer exists and `c()` is the suspend list-fetcher.
     */
    val adQueueFetchMethod: String? = "b",
    /**
     * Insert/list-ads method on [adQueueManager]. v1.26.1: non-suspend, took
     * the feed list and returned it. v1.26.2+: suspend, returns `List<ke.j>`
     * (we replace with empty list).
     */
    val adQueueInsertMethod: String = "c",
    /** Sibling of [adQueueManager]. Metadata names it `AdImpressionManager`. */
    val adImpressionManager: ClassTarget,
    /**
     * App-level analytics dispatcher. v1.24.x / v1.26.1: one class with
     * `void a()`, `void b(String)`, `void c(...events)` on AppAnalyticsManager
     * itself. v1.26.2+: the outward-facing `AppAnalyticsManager`
     * (`ld.a` / `od.a`) is a wrapper holding the real dispatcher
     * (`ld.c` / `od.c`) in its only field; the void methods live on that
     * inner class. This target is the class whose void methods we no-op.
     *
     * HOW TO LOCATE: grep `AppAnalyticsManager` in metadata blocks, then on
     * v1.26.2+ follow the wrapper to the sole field-typed class with
     * `void a()` / `void b(String)` / `void c(...)`.
     */
    val analyticsManager: ClassTarget,
    // ---------------------------- UI / Compose -------------------------------
    /**
     * Sidebar item renderer. v1.26.1: `j(modifier, icon, textResId, hasDivider, onClick, …)`.
     * v1.26.2+: split into `m(modifier, textResId, hasDivider, onClick, …)`
     * and `n(modifier, vectorIcon, textResId, hasDivider, onClick, …)`.
     *
     * HOW TO LOCATE: grep [resStringHelpCenter] usage; only the sidebar item
     * renderer consumes it.
     */
    val sidebarItemRenderer: ClassTarget,
    /**
     * Methods on [sidebarItemRenderer] to inspect for a help-center text
     * resource argument. v1.26.1: `["j"]`. v1.26.2/v1.27.0: `["m","n"]`.
     */
    val sidebarItemMethods: List<String> = listOf("j"),
    /**
     * Account drawer screen. Gem button + Truth Gems banner methods.
     *
     * HOW TO LOCATE: usually same package as [sidebarItemRenderer]. Grep
     * the `Truth Gems` literal or the Composable whose tree includes the
     * sidebar.
     */
    val accountDrawerScreen: ClassTarget,
    /**
     * Methods on [accountDrawerScreen] that render the gem button + Truth Gems
     * banner. v1.26.1: `["M","b0"]`. v1.26.2/v1.27.0: `["J0","d0"]`.
     */
    val accountDrawerGemMethods: List<String> = listOf("M", "b0"),
    /**
     * TopAppBar action factory; method renders the TRUTH+ upsell button.
     *
     * HOW TO LOCATE: grep the Truth+ subscription route literal
     * (`"truth-plus-modal-bottom-sheet"`) inside a top-app-bar Composable
     * factory. The Composable lambda is a small `i()` method.
     */
    val topAppBarFactory: ClassTarget,
    /** Method on [topAppBarFactory] that renders the TRUTH+ button. Usually `"i"`. */
    val topAppBarTruthPlusMethod: String = "i",
    /**
     * `NavDrawerAvatar`. Gem badge default-zero (grey gem) and count badge
     * (blue gem). v1.26.1: `["k","m"]`. v1.26.2/v1.27.0: `["i","j"]`.
     *
     * HOW TO LOCATE: jadx displays the file as `kotlin.AbstractC1695B` due to
     * a case-insensitive filesystem rename. Grep `NavDrawerAvatarKt` in
     * metadata, then read the `JADX INFO: renamed from: <pkg>.B` marker for
     * the actual JVM name.
     */
    val navDrawerAvatar: ClassTarget,
    /** Method names on [navDrawerAvatar] that draw the gem badge. */
    val navDrawerAvatarBadgeMethods: List<String> = listOf("k", "m"),
    /**
     * Bottom navigation tabs container.
     *
     * v1.26.1 and earlier: instance method `a()` returns `List<Tab>`. Use
     * [bottomNavTabsListMethod].
     *
     * v1.26.2+ (Compose nav rewrite): final class with static fields
     * `a` and `b`, each a `List<Tab>` singleton. Use
     * [bottomNavTabsStaticFields].
     *
     * HOW TO LOCATE: grep bottom-nav route literals (`"feeds"`, `"alerts"`,
     * `"discover"`) inside a class whose `<clinit>` builds singleton lists.
     */
    val bottomNavTabs: ClassTarget,
    /**
     * Instance method on [bottomNavTabs] returning the live `List<Tab>`.
     * Null on v1.26.2+ where the tab list lives in static fields.
     */
    val bottomNavTabsListMethod: String? = "a",
    /**
     * Static-field names on [bottomNavTabs] holding `List<Tab>` singletons.
     * Empty on v1.26.1 and earlier (the list comes from
     * [bottomNavTabsListMethod] instead).
     */
    val bottomNavTabsStaticFields: List<String> = emptyList(),
    /**
     * Bottom-nav AI-tab subclass.
     *
     * HOW TO LOCATE: in the Tab base (sibling of [bottomNavTabs]), the AI
     * tab references "AI" / "Truth Search". Inner classes encode as
     * `Outer$Inner` for [ClassTarget].
     *
     * Null on v1.26.2+ where the AI tab was removed entirely.
     */
    val bottomNavAiTab: ClassTarget? = null,
    /** HOW TO LOCATE: same Tab base as [bottomNavAiTab]; the Alerts tab's route is `"alerts"`. */
    val bottomNavAlertsTab: ClassTarget,
    /**
     * Per-route tab classes used by [BottomBarReorderHook]. Keys are route
     * strings (`"feeds"`, `"alerts"`); values are the singleton Tab subclass
     * returning that route.
     *
     * Populated for v1.26.2+ where the tab list lives in static fields and
     * we can reorder by reflection. Empty on older builds.
     */
    val bottomNavTabClasses: Map<String, ClassTarget> = emptyMap(),
    /**
     * SwipeableRow Composable. v1.26.1: `j(modifier, swipeToStartAction,
     * swipeToEndAction, state, content, …)`. v1.26.2/v1.27.0 renamed to
     * `i` / `e` respectively.
     *
     * HOW TO LOCATE: grep `SwipeableRow` in Kotlin metadata.
     */
    val swipeableRow: ClassTarget,
    /** Method on [swipeableRow] that renders the SwipeableRow Composable. */
    val swipeableRowMethod: String = "j",
    /**
     * R8-renamed package prefix of the Alerts screen Composables. Used in a
     * stack-frame match so we only neutralize SwipeableRow when the call
     * site is on the alerts screen. v1.24.8/v1.26.1: `"R8."`. v1.26.2:
     * `"A8."`. v1.27.0: `"B8."`.
     *
     * HOW TO LOCATE: `find /tmp/jadx_<v>/sources -name "*.java" -exec grep -l "AlertsScreenKt" {} +`,
     * then take the result's package prefix.
     */
    val alertsScreenPackagePrefix: String = "R8.",
    /**
     * Top-of-home-feed "live content" / podcast carousel. Introduced in
     * v1.27.0 under `features.liveContentCarouselEnabled`; doesn't exist on
     * v1.24.x / v1.26.1 / v1.26.2. Renderer is a static Composable on a file
     * class in `wd.*` (v1.27.0: `wd.j`).
     *
     * Null means the build has no live carousel; the hook silently no-ops
     * and the toggle is hidden from the UI.
     */
    val liveContentCarousel: ClassTarget? = null,
    /** Composable method on [liveContentCarousel] that renders the block. */
    val liveContentCarouselMethod: String = "c",
    /**
     * Detail-screen `EmbeddedAnnouncementCard` (sponsored/promoted card).
     * The home feed never calls this — only `Ma.m` (DetailReplyTruth) and
     * `Ma.p` (TruthDetail) do; see [homeAnnouncementRenderer] for the
     * home-feed equivalent.
     *
     * HOW TO LOCATE: grep `R.string.embedded_announcement_disclaimer` or
     * `R.string.featured_ads` consumers.
     */
    val embeddedAnnouncement: ClassTarget? = null,
    val embeddedAnnouncementMethods: List<String> = emptyList(),
    /**
     * Extra file classes whose Composables render parts of the home-feed
     * top strip (chip row, sponsored live card). All entries get no-op'd
     * under HideLiveCarousel; missing entries fall through silently.
     *
     * HOW TO LOCATE: grep `r.i("com.truthsocial...Live` source markers; any
     * file class with `LazyRow` over `LiveShow` belongs here.
     */
    val extraLiveRenderers: List<ClassTarget> = emptyList(),
    /**
     * `AdView.kt` Composable dispatched by `FeedItemType.NonNativeAd` —
     * distinct from [embeddedAnnouncement] (detail-screen only) and from
     * [homeAnnouncementRenderer] (home-feed Announcement).
     *
     * HOW TO LOCATE: grep `case 22:` in the feed-item switch (jadx
     * `Ja/O.java` on v1.27.0); the static call after the `AdItemState`
     * cast names this class.
     */
    val nonNativeAdRenderer: ClassTarget? = null,
    /**
     * Home-feed `AnnouncementItem` Composable — the actual renderer for
     * the "UFC Freedom 250 / Proudly sponsored by Truth Social / Learn
     * More" banner. Data model is
     * `com.truthsocial.core.data.models.announcements.Announcement`,
     * NOT `EmbeddedAnnouncement` (which is the detail-screen path).
     *
     * HOW TO LOCATE: grep `Announcement.kt` source markers; the entry
     * point holds a `static d(Announcement, …)`.
     */
    val homeAnnouncementRenderer: ClassTarget? = null,
    /**
     * The "Ask Perplexity AI" button on the Discover screen (v1.27.0:
     * `S8.E.p(String text, ImageVector icon, De.a onClick, boolean enabled,
     * modifier, m, i, i)`). Older builds may host the same button on a
     * different class; null means the build has no discoverable Perplexity
     * entry point and the hook no-ops.
     *
     * HOW TO LOCATE: grep `R.string.ask_anything` (value is "Ask Perplexity
     * AI" in v1.27.0). Find the file consuming the resource id, then trace
     * the Composable call site.
     */
    val askPerplexityButton: ClassTarget? = null,
    /** Composable method on [askPerplexityButton] (v1.27.0: `"p"`). */
    val askPerplexityButtonMethod: String = "p",
    /**
     * `AppBuildInfo` data class. R8 inlines its property accessors at
     * every call site, so the only callable surface left is `toString()`
     * (returns `AppBuildInfo(versionCode=…, versionName=…, …)`). That
     * is what [com.example.mtga.hooks.VersionSuffixHook] rewrites to tag
     * analytics / crashlytics payloads.
     *
     * HOW TO LOCATE: dexdump for `"AppBuildInfo(versionCode="` —
     * the declaring class is the data class.
     */
    val appBuildInfo: ClassTarget? = null,
    /**
     * Truth Search AI use case. Hilt injects by FQN on v1.24.8 so the
     * original class survives. v1.26.1+ minifies it (becomes a no-op holder).
     */
    val searchAiUseCase: ClassTarget,
    /**
     * Premium-feature gate helper. Static functions on `TruthSocialUser`:
     *
     *   editsEnabled / scheduleEnabled / geofence / editsVisible / scheduleVisible
     *
     * R8-assigned letters drift between builds; see [PremiumGateMethods].
     *
     * HOW TO LOCATE: grep the `smsCountry` literal. The geofence helper is
     * the only consumer outside the data model itself.
     */
    val premiumGateHelper: ClassTarget,
    /** Method names on [premiumGateHelper] for each gate check. */
    val premiumGateMethods: PremiumGateMethods =
        PremiumGateMethods(
            editsEnabled = "a",
            scheduleEnabled = "c",
            geofence = "d",
            editsVisible = "e",
            scheduleVisible = "g",
        ),
    /**
     * Truth Compose post-action ViewModel. Wraps the Schedule click.
     *
     * HOW TO LOCATE: grep Kotlin metadata for `TruthComposeViewModel` or
     * `composer` package paths.
     */
    val composerViewModel: ClassTarget,
    /**
     * ViewModel method called by the Schedule button; branches into upsell.
     *
     * HOW TO LOCATE: in [composerViewModel] metadata, the source parameter
     * list mentions `publish: Boolean`. The matching R8-renamed name is in
     * the metadata's d2 array.
     */
    val composerScheduleClickMethod: String,
    /**
     * NavHandler. Instance method for `navigate(Route, options)`.
     *
     * HOW TO LOCATE: grep the `"truth-plus-modal-bottom-sheet"` route
     * literal. Call site is `navHandler.<navigate>(route, options)`.
     */
    val navHandler: ClassTarget,
    /** HOW TO LOCATE: from the [navHandler] call-site grep, read the method name. R8 single-letter, usually `d`. */
    val navHandlerNavigateMethod: String,
    /**
     * Route subclass for `truth-plus-modal-bottom-sheet`. Inner classes
     * encode as `Outer$a` for [ClassTarget].
     */
    val truthPlusUpsellRoute: ClassTarget,
    /** HOW TO LOCATE: grep the `"premium-feature-roadblock-dialog"` literal. */
    val premiumFeatureRoadblockRoute: ClassTarget,
    // ---------------------------- Data models --------------------------------
    /**
     * `Feed` model class. v1.24.x/v1.26.1: `com.truthsocial.app.data.models.feeds.Feed`.
     * v1.26.2+: moved to `com.truthsocial.core.data.models.feeds.Feed`.
     */
    val feedClass: ClassTarget =
        ClassTarget("com.truthsocial.app.data.models.feeds.Feed"),
    /**
     * `Features` model class. v1.24.x/v1.26.1: `com.truthsocial.app.data.models.Features`.
     * v1.26.2+: moved to `com.truthsocial.core.data.models.Features`.
     * v1.26.2 added `predictionsEnabled` (idx 8) and `videoScrollingEnabled` (idx 9).
     * v1.27.0 additionally added `liveContentCarouselEnabled` (idx 10).
     * Indices 0–7 (the legacy fields) are stable across all builds.
     */
    val featuresClass: ClassTarget =
        ClassTarget("com.truthsocial.app.data.models.Features"),
    // ---------------------------- Preferences screen -------------------------
    /**
     * Preferences-screen injection strategy for this build; see
     * [PreferencesInjectorKind]. The fields below ([preferencesBuilder],
     * [preferencesBuilderMethod], [preferencesSection], [preferencesTextRow])
     * are only consulted under [PreferencesInjectorKind.Legacy]. Modern
     * builds (v1.26.2+) ignore them.
     */
    val preferencesInjector: PreferencesInjectorKind = PreferencesInjectorKind.Legacy,
    /**
     * `PreferencesScreen` builder file class. Appends sections to the prefs
     * root.
     *
     * HOW TO LOCATE: grep `"preferences/all"` (the route), find the screen
     * Composable, trace to the helper. The helper is an `abstract` Kotlin
     * file class (static-only utility).
     */
    val preferencesBuilder: ClassTarget,
    /**
     * Static method on [preferencesBuilder] populating the section list.
     *
     * HOW TO LOCATE: takes the prefs root (`ic.f`) as first arg and appends
     * `ic.b` sections to its ArrayList field. Single-letter R8 name, usually `p`.
     */
    val preferencesBuilderMethod: String,
    /**
     * Section type: 2 SharedPreferences + String<title> + boolean +
     * ArrayList<items>.
     *
     * The hook uses type-based field discovery, so individual field names
     * within this class don't need to be tracked separately.
     */
    val preferencesSection: ClassTarget,
    /** Clickable text row. Same package as [preferencesSection], wider field set + `Mc.a` click callback. Type-based field discovery. */
    val preferencesTextRow: ClassTarget,
    // --- Modern (v1.26.2+) preferences screen --------------------------------
    /**
     * File-class hosting the static `p()` that builds the Preferences root's
     * section list. Hooking `p()` after the fact lets us append an "MTGA
     * Settings" section to the ArrayList field on the root.
     *
     * v1.26.2: `na.j` (jadx file `na/j.java`).
     * v1.27.0: `oa.k` (jadx file `oa/k.java`).
     * Null on Legacy builds; only consulted by [PreferencesInjectorKind.Modern].
     *
     * HOW TO LOCATE: grep references to the route-id constant `U.a.f<N>`
     * (the `preferences/all` route subclass) inside `oa/` / `na/`. The file
     * that builds the screen also exposes a top-level `p(<root>, …)`
     * mutating an ArrayList field on its first argument.
     */
    val modernPreferencesBuilder: ClassTarget? = null,
    /** Method name on [modernPreferencesBuilder]. Typically `"p"`. */
    val modernPreferencesBuilderMethod: String = "p",
    /**
     * Screen-root data class. First field "settings" SharedPreferences, second
     * "user" SharedPreferences, third `ArrayList<Section>`.
     *
     * v1.26.2: `Ud.g`. v1.27.0: `Zd.f`. Null on Legacy.
     */
    val modernPreferencesRoot: ClassTarget? = null,
    /**
     * Section data class. `(SharedPreferences, SharedPreferences)` ctor, 3rd
     * field `String title`, 5th field `ArrayList<Item>`.
     *
     * v1.26.2: `Ud.b`. v1.27.0: `Zd.b`. Null on Legacy.
     */
    val modernPreferencesSection: ClassTarget? = null,
    /**
     * Text-row data class. `(SharedPreferences, SharedPreferences, <h0/s0>)`
     * ctor, 3rd field `String title`, 4th `String subtitle`, last
     * `Function0 onClick`.
     *
     * v1.26.2: `Ud.d`. v1.27.0: `Zd.d`. Null on Legacy.
     */
    val modernPreferencesTextRow: ClassTarget? = null,
    /**
     * R8 rename of `kotlin.jvm.functions.Function0` used by the Modern
     * Text-row onClick field. v1.26.2: `ye.a` (InterfaceC5755a). v1.27.0:
     * `De.a`. Null on Legacy builds (they reuse [kotlinFunction0]).
     */
    val modernKotlinFunction0: ClassTarget? = null,
    /**
     * R8 rename of `kotlin.jvm.functions.Function0`. The `Mc` package
     * contains all `Function0..22` renames; `a` is reliably Function0
     * (no-arg).
     */
    val kotlinFunction0: ClassTarget,
    /**
     * R8 rename of `kotlin.Unit`. Singleton field whose `toString` returns
     * `"kotlin.Unit"`, lives in the `yc` Kotlin-stdlib runtime package.
     */
    val kotlinUnit: ClassTarget,
    // ---------------------------- Resources ----------------------------------
    /**
     * `R.string.help_center` numeric id. Resource ids are stable across R8
     * minify but can shift between releases when other resources are added.
     *
     * HOW TO LOCATE: grep `R.java` for `help_center`, or
     * `aapt2 dump resources base.apk | grep string/help_center`.
     */
    val resStringHelpCenter: Int,
    /**
     * `R.string.version` numeric id (template: `"Version: %1$s"`).
     * Defaults to `0` so the [com.example.mtga.hooks.VersionSuffixHook]
     * Resources.getString hook silently skips on uncalibrated builds.
     */
    val resStringVersion: Int = 0,
)

/**
 * Strategy for injecting the "MTGA Settings" row into Truth Social's
 * Preferences screen. The architecture changed in v1.26.2:
 *
 *  - [Legacy]: v1.24.6 / v1.24.8 / v1.26.1. The screen is built by a single
 *    static helper (`sa.j.p(prefsRoot, …)`) appending `ic.b` sections; each
 *    section holds `ic.d` rows with an `Mc.a` (Function0) click callback. We
 *    hook `p`, append our own section, set the click to launch
 *    [com.example.mtga.SettingsActivity].
 *
 *  - [Modern]: v1.26.2 / v1.27.0. The `ic.b` / `ic.d` data classes are gone;
 *    the screen is composed via Compose-only Composables under `oa.` / `na.`
 *    with a different section/item model
 *    ([com.example.mtga.hooks.preferences.ModernPreferencesInjector]). The
 *    triple-tap fallback from [com.example.mtga.hooks.InAppSettingsHook]
 *    remains active so a calibration mismatch can't strand the user.
 */
enum class PreferencesInjectorKind {
    Legacy,
    Modern,
}

data class PremiumGateMethods(
    /** `boolean foo(TruthSocialUser)` returning `features.editsEnabled`. */
    val editsEnabled: String,
    /** `boolean foo(TruthSocialUser)` returning `features.scheduleEnabled`. */
    val scheduleEnabled: String,
    /** `private boolean foo(TruthSocialUser)` returning `smsCountry == "US"`. */
    val geofence: String,
    /** `boolean foo(TruthSocialUser)` returning `editsVisible && geofence`. */
    val editsVisible: String,
    /** `boolean foo(TruthSocialUser)` returning `scheduleVisible && geofence`. */
    val scheduleVisible: String,
)

/** An obfuscated class name. No fallbacks; a wrong name should fail loudly. */
data class ClassTarget(
    val name: String,
) {
    /** Convert dot-form class name to a DEX type descriptor: `v7.d` → `Lv7/d;`, `C6.f$f` → `LC6/f$f;`. */
    val descriptor: String
        get() = "L${name.replace('.', '/')};"
}

data class BuildId(
    val versionName: String,
    val versionCode: Int,
    /** SHA-256 of base.apk extracted from the apkmirror bundle. Hex lowercase. */
    val baseApkSha256: String,
)

// v1.26.1 R8 hashing is mostly stable against v1.24.8. Obfuscated names for
// hooks/patches are identical except:
//   - searchAiUseCase: the FQN `com.truthsocial.app.domain.usecase.ai.SearchAIUseCase`
//     no longer survives R8; the class is renamed to `x8.l` (body emptied to
//     a no-op holder, so DisableSearchAiPatch becomes defensive, and the
//     runtime hook silently fails — Truth Search AI label blanking still
//     works via `blankStringResource`).
//   - resStringHelpCenter: shifted by 3 ids as new string resources were
//     inserted above it.
private val TargetsV1_26_1 =
    TargetSet(
        buildId =
            BuildId(
                versionName = "1.26.1",
                versionCode = 1254,
                baseApkSha256 = "2e974acac3ec18b1dfc7ccf98c49159896fe391f2ee0d1606581315f4abda158",
            ),
        integrityInterceptor = ClassTarget("Q6.b"),
        integrityInterceptMethod = "a",
        chainRequestField = "e",
        chainProceedMethod = "b",
        integrityChain = ClassTarget("Be.h"),
        okhttpRequest = ClassTarget("we.B"),
        okhttpResponse = ClassTarget("we.H"),
        feedIdMethod = "i",
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "l",
        retrofitOkHttpCallRequestMethod = "p",
        feedsRepository = ClassTarget("g8.h"),
        appStateManager = ClassTarget("O6.b"),
        adQueueManager = ClassTarget("v7.d"),
        // `b` is a suspend `(String, Cc.c) → Object` — redirecting to
        // null breaks the coroutine resume and empties the home timeline.
        adQueueFetchMethod = null,
        adImpressionManager = ClassTarget("v7.a"),
        analyticsManager = ClassTarget("ac.c"),
        sidebarItemRenderer = ClassTarget("E6.f"),
        accountDrawerScreen = ClassTarget("E6.y"),
        topAppBarFactory = ClassTarget("Xa.e"),
        navDrawerAvatar = ClassTarget("X5.B"),
        bottomNavTabs = ClassTarget("C6.g"),
        bottomNavAiTab = ClassTarget("C6.f\$f"),
        bottomNavAlertsTab = ClassTarget("C6.f\$a"),
        swipeableRow = ClassTarget("c6.d"),
        searchAiUseCase = ClassTarget("x8.l"),
        askPerplexityButton = ClassTarget("P8.c"),
        askPerplexityButtonMethod = "e",
        appBuildInfo = ClassTarget("s7.c"),
        premiumGateHelper = ClassTarget("L6.U"),
        composerViewModel = ClassTarget("db.P"),
        composerScheduleClickMethod = "x1",
        navHandler = ClassTarget("Ub.n"),
        navHandlerNavigateMethod = "d",
        truthPlusUpsellRoute = ClassTarget("Wb.M\$a"),
        premiumFeatureRoadblockRoute = ClassTarget("Wb.A\$a"),
        preferencesBuilder = ClassTarget("sa.j"),
        preferencesBuilderMethod = "p",
        preferencesSection = ClassTarget("ic.b"),
        preferencesTextRow = ClassTarget("ic.d"),
        kotlinFunction0 = ClassTarget("Mc.a"),
        kotlinUnit = ClassTarget("yc.v"),
        resStringHelpCenter = 0x7f120255,
        resStringVersion = 0x7f120595,
        // New fields all take their data-class defaults, which match v1.26.1.
    )

private val TargetsV1_24_8 =
    TargetSet(
        buildId =
            BuildId(
                versionName = "1.24.8",
                versionCode = 1228,
                baseApkSha256 = "bcca813e2920602f0a9908240c537dc1d9ee6b6a90213e2b0be03e6458f35c1a",
            ),
        integrityInterceptor = ClassTarget("Q6.b"),
        integrityInterceptMethod = "a",
        chainRequestField = "e",
        chainProceedMethod = "b",
        integrityChain = ClassTarget("Be.h"),
        okhttpRequest = ClassTarget("we.B"),
        okhttpResponse = ClassTarget("we.H"),
        feedIdMethod = "i",
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "l",
        retrofitOkHttpCallRequestMethod = "p",
        feedsRepository = ClassTarget("g8.h"),
        appStateManager = ClassTarget("O6.b"),
        adQueueManager = ClassTarget("v7.d"),
        // `b` is suspend (Continuation last arg) — redirecting to null
        // breaks the coroutine resume and empties the home timeline.
        adQueueFetchMethod = null,
        adImpressionManager = ClassTarget("v7.a"),
        analyticsManager = ClassTarget("ac.c"),
        sidebarItemRenderer = ClassTarget("E6.f"),
        accountDrawerScreen = ClassTarget("E6.y"),
        topAppBarFactory = ClassTarget("Xa.e"),
        navDrawerAvatar = ClassTarget("X5.B"),
        bottomNavTabs = ClassTarget("C6.g"),
        bottomNavTabsStaticFields = listOf("b"),
        bottomNavAiTab = ClassTarget("C6.f\$f"),
        bottomNavAlertsTab = ClassTarget("C6.f\$a"),
        bottomNavTabClasses =
            mapOf(
                "alerts" to ClassTarget("C6.f\$a"),
                "discover" to ClassTarget("C6.f\$b"),
                "groups" to ClassTarget("C6.f\$c"),
                "feeds" to ClassTarget("C6.f\$d"),
                "chats" to ClassTarget("C6.f\$e"),
            ),
        swipeableRow = ClassTarget("c6.d"),
        homeAnnouncementRenderer = ClassTarget("Pa.a"),
        // FQN does NOT survive R8 on v1.24.x — only the `*_Factory` Hilt
        // accessor retains it. Runtime class is the renamed no-op holder.
        searchAiUseCase = ClassTarget("x8.l"),
        appBuildInfo = ClassTarget("s7.c"),
        premiumGateHelper = ClassTarget("L6.U"),
        composerViewModel = ClassTarget("db.P"),
        composerScheduleClickMethod = "x1",
        navHandler = ClassTarget("Ub.n"),
        navHandlerNavigateMethod = "d",
        truthPlusUpsellRoute = ClassTarget("Wb.M\$a"),
        premiumFeatureRoadblockRoute = ClassTarget("Wb.A\$a"),
        preferencesBuilder = ClassTarget("sa.j"),
        preferencesBuilderMethod = "p",
        preferencesSection = ClassTarget("ic.b"),
        preferencesTextRow = ClassTarget("ic.d"),
        kotlinFunction0 = ClassTarget("Mc.a"),
        kotlinUnit = ClassTarget("yc.v"),
        resStringHelpCenter = 0x7f120252,
        resStringVersion = 0x7f120592,
    )

private val TargetsV1_24_10 =
    TargetSet(
        buildId =
            BuildId(
                versionName = "1.24.10",
                versionCode = 1230,
                baseApkSha256 = "06b80a1e5771d42d275b64b297e35b7e77fc62413a1e6a42f47624246049abab",
            ),
        integrityInterceptor = ClassTarget("Q6.b"),
        integrityInterceptMethod = "a",
        chainRequestField = "e",
        chainProceedMethod = "b",
        integrityChain = ClassTarget("Be.h"),
        okhttpRequest = ClassTarget("we.B"),
        okhttpResponse = ClassTarget("we.H"),
        feedIdMethod = "i",
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "l",
        retrofitOkHttpCallRequestMethod = "p",
        feedsRepository = ClassTarget("g8.h"),
        appStateManager = ClassTarget("O6.b"),
        adQueueManager = ClassTarget("v7.d"),
        // `b` is a suspend `(String, Cc.c) → Object` despite the legacy
        // R8 names — redirecting to null breaks the coroutine resume and
        // empties the home timeline.
        adQueueFetchMethod = null,
        adImpressionManager = ClassTarget("v7.a"),
        analyticsManager = ClassTarget("ac.c"),
        sidebarItemRenderer = ClassTarget("E6.f"),
        accountDrawerScreen = ClassTarget("E6.y"),
        topAppBarFactory = ClassTarget("Xa.e"),
        navDrawerAvatar = ClassTarget("X5.B"),
        bottomNavTabs = ClassTarget("C6.g"),
        bottomNavTabsStaticFields = listOf("b"),
        bottomNavAiTab = ClassTarget("C6.f\$f"),
        bottomNavAlertsTab = ClassTarget("C6.f\$a"),
        bottomNavTabClasses =
            mapOf(
                "alerts" to ClassTarget("C6.f\$a"),
                "discover" to ClassTarget("C6.f\$b"),
                "groups" to ClassTarget("C6.f\$c"),
                "feeds" to ClassTarget("C6.f\$d"),
                "chats" to ClassTarget("C6.f\$e"),
            ),
        swipeableRow = ClassTarget("c6.d"),
        // Composer interface is `s0.m` on this build (v1.27.x renamed to
        // `v0.m`); [UICleanupHook]'s composer-prefix regex picks it up.
        homeAnnouncementRenderer = ClassTarget("Pa.a"),
        // FQN doesn't survive R8 on v1.24.x — `*_Factory` retains it but
        // the use case itself is the renamed no-op holder.
        searchAiUseCase = ClassTarget("x8.l"),
        appBuildInfo = ClassTarget("s7.c"),
        premiumGateHelper = ClassTarget("L6.U"),
        composerViewModel = ClassTarget("db.P"),
        composerScheduleClickMethod = "x1",
        navHandler = ClassTarget("Ub.n"),
        navHandlerNavigateMethod = "d",
        truthPlusUpsellRoute = ClassTarget("Wb.M\$a"),
        premiumFeatureRoadblockRoute = ClassTarget("Wb.A\$a"),
        preferencesBuilder = ClassTarget("sa.j"),
        preferencesBuilderMethod = "p",
        preferencesSection = ClassTarget("ic.b"),
        preferencesTextRow = ClassTarget("ic.d"),
        kotlinFunction0 = ClassTarget("Mc.a"),
        kotlinUnit = ClassTarget("yc.v"),
        resStringHelpCenter = 0x7f120255,
        resStringVersion = 0x7f120595,
    )

// v1.24.6 obfuscated names are identical to v1.24.8 — R8 hashing was stable
// between these releases. Registered separately so `forVersionCode` matches
// the running APK exactly and any future drift is caught loudly. Not
// deploy-tested (the rooted AVD has v1.24.8 installed and Android disallows
// downgrades); verified at compile time and via jadx symbol equivalence.
private val TargetsV1_24_6 =
    TargetSet(
        buildId =
            BuildId(
                versionName = "1.24.6",
                versionCode = 1226,
                baseApkSha256 = "6108f4127e7ec04be40454ab083bfde870f0055ce7e2511e9f730418c2d2cc93",
            ),
        integrityInterceptor = ClassTarget("Q6.b"),
        integrityInterceptMethod = "a",
        chainRequestField = "e",
        chainProceedMethod = "b",
        integrityChain = ClassTarget("Be.h"),
        okhttpRequest = ClassTarget("we.B"),
        okhttpResponse = ClassTarget("we.H"),
        feedIdMethod = "i",
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "l",
        retrofitOkHttpCallRequestMethod = "p",
        feedsRepository = ClassTarget("g8.h"),
        appStateManager = ClassTarget("O6.b"),
        adQueueManager = ClassTarget("v7.d"),
        // `b` is suspend; redirecting to null breaks the coroutine
        // resume and empties the home timeline.
        adQueueFetchMethod = null,
        adImpressionManager = ClassTarget("v7.a"),
        analyticsManager = ClassTarget("ac.c"),
        sidebarItemRenderer = ClassTarget("E6.f"),
        accountDrawerScreen = ClassTarget("E6.y"),
        topAppBarFactory = ClassTarget("Xa.e"),
        navDrawerAvatar = ClassTarget("X5.B"),
        bottomNavTabs = ClassTarget("C6.g"),
        bottomNavTabsStaticFields = listOf("b"),
        bottomNavAiTab = ClassTarget("C6.f\$f"),
        bottomNavAlertsTab = ClassTarget("C6.f\$a"),
        bottomNavTabClasses =
            mapOf(
                "alerts" to ClassTarget("C6.f\$a"),
                "discover" to ClassTarget("C6.f\$b"),
                "groups" to ClassTarget("C6.f\$c"),
                "feeds" to ClassTarget("C6.f\$d"),
                "chats" to ClassTarget("C6.f\$e"),
            ),
        swipeableRow = ClassTarget("c6.d"),
        homeAnnouncementRenderer = ClassTarget("Pa.a"),
        // FQN doesn't survive R8 on v1.24.x — runtime class is the
        // renamed no-op holder.
        searchAiUseCase = ClassTarget("x8.l"),
        appBuildInfo = ClassTarget("s7.b"),
        premiumGateHelper = ClassTarget("L6.U"),
        composerViewModel = ClassTarget("db.P"),
        composerScheduleClickMethod = "x1",
        navHandler = ClassTarget("Ub.n"),
        navHandlerNavigateMethod = "d",
        truthPlusUpsellRoute = ClassTarget("Wb.M\$a"),
        premiumFeatureRoadblockRoute = ClassTarget("Wb.A\$a"),
        preferencesBuilder = ClassTarget("sa.j"),
        preferencesBuilderMethod = "p",
        preferencesSection = ClassTarget("ic.b"),
        preferencesTextRow = ClassTarget("ic.d"),
        kotlinFunction0 = ClassTarget("Mc.a"),
        kotlinUnit = ClassTarget("yc.v"),
        resStringHelpCenter = 0x7f120252,
        resStringVersion = 0x7f120592,
    )

// v1.24.8/v1.26.1 share `R8.*` for the alerts screen package and Legacy
// preferences injector — no new fields need overriding. Defaults for
// alertsScreenPackagePrefix = "R8." and Modern-only fields = null are
// correct for these builds.

// v1.26.2 churn:
//   - Compose Navigation rewrite: bottom-nav tabs moved from `C6.g.a()` to
//     static fields on `Zc.j` (predictions list `a`, chats list `b`). The
//     AI tab was removed entirely — `bottomNavAiTab` is null. New tab
//     classes live as `Zc.c..h` keyed by route id.
//   - `Feed` / `Features` data models moved from `com.truthsocial.app` to
//     `com.truthsocial.core`. `Features` gained `predictionsEnabled` (idx 8)
//     and `videoScrollingEnabled` (idx 9); indices 0–7 unchanged.
//   - AdQueueManager refactor: dropped `b()` (fetchAd). `c()` is now a
//     suspend function returning `List<? extends ke.j>` (we replace with
//     empty list); `e(timelineId, adIndexes, zone, maxListSize, indexOffset)`
//     is the void writer (we no-op).
//   - AppStateManagerImpl tab-select methods drifted from `c/e` to `b/j`;
//     clear-badge method from `g` to `e`.
//   - Sidebar item renderer split from a single `j()` into `m()` + `n()`.
//   - NavDrawerAvatar gem badge methods renamed `k/m` → `i/j`.
//   - Account drawer gem methods renamed `M/b0` → `J0/d0`.
//   - `preferencesBuilder`, `composerViewModel`, `kotlinFunction0`,
//     `kotlinUnit` not re-verified for v1.26.2 (low priority for the
//     features that consume them). Values inherited from v1.26.1 — may be
//     wrong; the matching hooks silently no-op if so.
private val TargetsV1_26_2 =
    TargetSet(
        buildId =
            BuildId(
                versionName = "1.26.2",
                versionCode = 1256,
                baseApkSha256 = "2fa0e3c8dea0967e375a7e7aec135c4bb60ea67c9d6e577010f1496aad291fa3",
            ),
        integrityInterceptor = ClassTarget("Q6.b"),
        integrityInterceptMethod = "a",
        // og.g is the new chain class (renamed from Be.h in v1.26.1). Field
        // and method letters are stable.
        chainRequestField = "e",
        chainProceedMethod = "b",
        integrityChain = ClassTarget("og.g"),
        okhttpRequest = ClassTarget("jg.D"),
        okhttpResponse = ClassTarget("jg.J"),
        feedIdMethod = null,
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "l",
        retrofitOkHttpCallRequestMethod = "p",
        feedsRepository = ClassTarget("O7.i"),
        appStateManager = ClassTarget("G6.a"),
        appStateTabSelectMethods = listOf("b", "j"),
        appStateClearBadgeMethod = "e",
        adQueueManager = ClassTarget("i7.e"),
        adQueueFetchMethod = null, // b() no longer exists
        adQueueInsertMethod = "c", // suspend, returns List<ke.j>
        adImpressionManager = ClassTarget("i7.b"),
        // `ld.a` is a wrapper holding a `ld.c` field; `ld.c` carries the
        // void analytics-dispatch methods.
        analyticsManager = ClassTarget("ld.c"),
        sidebarItemRenderer = ClassTarget("y6.c"),
        sidebarItemMethods = listOf("m", "n"),
        accountDrawerScreen = ClassTarget("y6.k"),
        // Only the NonPremiumTruthGemsBannerRow (`d0`). The drawer-header
        // Composable (`J0`) was previously listed but renders the avatar +
        // display name + follower/following counts as well as the gems
        // icon; DO_NOTHING wiped the whole header. The gem badge on the
        // avatar itself is still suppressed via [navDrawerAvatar]
        // (`Cc.p.i` / `Cc.p.j`).
        accountDrawerGemMethods = listOf("f0"),
        // v1.26.2: `Ta.e` (jadx `C1623e.java`) is `FeedsTopBarContentKt`,
        // the Composable rendering the TRUTH+ icon action via
        // `i(v0 CenterAlignedTopAppBar, m, i)`. DO_NOTHING hides only that
        // single action.
        //
        // `lb.v` exists too but is the modal shown when the button is
        // tapped (onCloseClicked / onStartWatchingClicked), not the icon.
        topAppBarFactory = ClassTarget("Ta.e"),
        topAppBarTruthPlusMethod = "i",
        navDrawerAvatar = ClassTarget("Cc.p"),
        navDrawerAvatarBadgeMethods = listOf("i", "j"),
        bottomNavTabs = ClassTarget("Zc.j"),
        bottomNavTabsListMethod = null,
        bottomNavTabsStaticFields = listOf("a", "b"),
        bottomNavAiTab = null, // AI tab removed in v1.26.2
        bottomNavAlertsTab = ClassTarget("Zc.c"),
        bottomNavTabClasses =
            mapOf(
                "alerts" to ClassTarget("Zc.c"),
                "discover" to ClassTarget("Zc.d"),
                "groups" to ClassTarget("Zc.e"),
                "feeds" to ClassTarget("Zc.f"),
                "chats" to ClassTarget("Zc.g"),
                "predictions" to ClassTarget("Zc.h"),
            ),
        swipeableRow = ClassTarget("Xg.b"),
        swipeableRowMethod = "i",
        alertsScreenPackagePrefix = "A8.",
        // v1.26.2: SearchAIUseCase Hilt factory's newInstance() returns `f8.l`.
        searchAiUseCase = ClassTarget("f8.l"),
        premiumGateHelper = ClassTarget("D6.C"),
        premiumGateMethods =
            PremiumGateMethods(
                editsEnabled = "a",
                scheduleEnabled = "c",
                geofence = "d",
                editsVisible = "e",
                scheduleVisible = "g",
            ),
        composerViewModel = ClassTarget("Za.Z"),
        composerScheduleClickMethod = "P1",
        navHandler = ClassTarget("Rb.s"),
        navHandlerNavigateMethod = "d",
        truthPlusUpsellRoute = ClassTarget("Tb.M\$a"),
        premiumFeatureRoadblockRoute = ClassTarget("Tb.B\$a"),
        feedClass = ClassTarget("com.truthsocial.core.data.models.feeds.Feed"),
        featuresClass = ClassTarget("com.truthsocial.core.data.models.Features"),
        // Preferences screen rewritten. Legacy `ic.b`/`ic.d` injection no
        // longer applies. Modern injector targets `na.j.p(Ud.g, …)` and
        // appends an MTGA section to the screen root's ArrayList.
        preferencesInjector = PreferencesInjectorKind.Modern,
        // Below five fields are unused on Modern but kept populated so the
        // data class stays uniform; values inherited from v1.26.1.
        preferencesBuilder = ClassTarget("sa.j"),
        preferencesBuilderMethod = "p",
        preferencesSection = ClassTarget("ic.b"),
        preferencesTextRow = ClassTarget("ic.d"),
        kotlinFunction0 = ClassTarget("Mc.a"),
        kotlinUnit = ClassTarget("yc.v"),
        modernPreferencesBuilder = ClassTarget("na.j"),
        modernPreferencesBuilderMethod = "p",
        modernPreferencesRoot = ClassTarget("Ud.g"),
        modernPreferencesSection = ClassTarget("Ud.b"),
        modernPreferencesTextRow = ClassTarget("Ud.d"),
        modernKotlinFunction0 = ClassTarget("ye.a"),
        appBuildInfo = ClassTarget("Zb.a"),
        // Home-feed Announcement (sponsored banner) Composable lives at
        // `Ka.a.d(Announcement, Function1, Composer, ii, default)V`, source
        // marker `Announcement.kt:54`. Same shape as v1.27.0's `La.a` and
        // v1.27.1's `Na.a`; the feature first ships in v1.26.2.
        homeAnnouncementRenderer = ClassTarget("Ka.a"),
        // Data-layer FeedItem filter (Compose-safe) — same fix as v1.27.1.
        // `Ae.a.q(List, TimelineType)` builds the timeline list; elements are
        // `ed.a` with the FeedItemType in field `b`.
        feedItemMapper = ClassTarget("Ae.a"),
        feedItemMapperMethod = "q",
        feedItemWrapper = ClassTarget("ed.a"),
        resStringHelpCenter = 0x7f1202be,
        resStringVersion = 0x7f1206b7,
    )

// v1.27.0 — best-effort calibration. Same structural churn as v1.26.2 with a
// one-package-letter shift across the board. Several low-priority fields
// inherited from v1.26.2 / v1.26.1 are unverified.
private val TargetsV1_27_0 =
    TargetSet(
        buildId =
            BuildId(
                versionName = "1.27.0",
                versionCode = 1258,
                baseApkSha256 = "267851a53a8986a42a50dafb23f4666b5c02f5533f4075a9b68fe3d2927836ab",
            ),
        integrityInterceptor = ClassTarget("I6.f"),
        integrityInterceptMethod = "a",
        // tg.g is the v1.27 chain class.
        chainRequestField = "e",
        chainProceedMethod = "b",
        integrityChain = ClassTarget("tg.g"),
        okhttpRequest = ClassTarget("og.E"),
        okhttpResponse = ClassTarget("og.K"),
        feedIdMethod = null,
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "u",
        retrofitOkHttpCallRequestMethod = "G",
        // Inherited from v1.26.2; feeds repo class shift not re-verified for v1.27.
        feedsRepository = ClassTarget("P7.i"),
        appStateManager = ClassTarget("H6.a"),
        appStateTabSelectMethods = listOf("c", "e"),
        appStateClearBadgeMethod = "d",
        adQueueManager = ClassTarget("j7.e"),
        adQueueFetchMethod = null,
        adQueueInsertMethod = "c",
        adImpressionManager = ClassTarget("j7.b"),
        // v1.27.0 mirrors v1.26.2's wrapper layout: `od.a` holds an `od.c`
        // field, `od.c` carries the void analytics dispatch methods.
        analyticsManager = ClassTarget("od.c"),
        // Inherited from v1.26.2; methods m/n are the same shape.
        sidebarItemRenderer = ClassTarget("z6.c"),
        sidebarItemMethods = listOf("m", "n"),
        accountDrawerScreen = ClassTarget("z6.j"),
        // See v1.26.2 note above: J0 is the full drawer header, not a
        // gem-only Composable. Leaving it out keeps the follower/following
        // counts visible.
        accountDrawerGemMethods = listOf("f0"),
        // See v1.26.2 note above: `Ua.e` (jadx `C1630e.java`) is the icon
        // factory; `mb.v` is the modal.
        topAppBarFactory = ClassTarget("Ua.e"),
        topAppBarTruthPlusMethod = "i",
        navDrawerAvatar = ClassTarget("Fc.p"),
        navDrawerAvatarBadgeMethods = listOf("i", "j"),
        bottomNavTabs = ClassTarget("cd.j"),
        bottomNavTabsListMethod = null,
        bottomNavTabsStaticFields = listOf("a", "b"),
        bottomNavAiTab = null,
        bottomNavAlertsTab = ClassTarget("cd.c"),
        bottomNavTabClasses =
            mapOf(
                "alerts" to ClassTarget("cd.c"),
                "discover" to ClassTarget("cd.d"),
                "groups" to ClassTarget("cd.e"),
                "feeds" to ClassTarget("cd.f"),
                "chats" to ClassTarget("cd.g"),
                "predictions" to ClassTarget("cd.h"),
            ),
        swipeableRow = ClassTarget("t2.a"),
        swipeableRowMethod = "e",
        alertsScreenPackagePrefix = "B8.",
        liveContentCarousel = ClassTarget("wd.j"),
        // wd.j is the LiveContentCarousel file class. Every public Composable
        // on it (a = CarouselHeader, b = LiveContentCard, c = main entry,
        // d = WatchOnTruthPlusOverlay) can render an independently-mounted
        // fragment of the bar, so we DO_NOTHING all of them.
        liveContentCarouselMethod = "c",
        embeddedAnnouncement = ClassTarget("ud.d"),
        // ud.d hosts the sponsored "embedded announcement" card. Each
        // public method (a..i) is a distinct Composable that can be picked
        // up at render time depending on the announcement's shape and the
        // user's Truth+ status; the only safe option is "no-op everything
        // returning Unit and taking an `InterfaceC5120m`". The list of
        // method letters below is exhaustive for v1.27.0.
        embeddedAnnouncementMethods = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"),
        // AvatarCarousel `Ha.o` is intentionally NOT suppressed. Despite
        // the visual similarity to a "live avatars" row at the top of the
        // home feed, this Composable is the user-filter chip strip — tap
        // an avatar to show only that account's posts. It's a useful
        // feature, not an ad surface.
        extraLiveRenderers =
            listOf(
                // LiveCarouselChipStrip — pill row of "Foul Play",
                // "Cops Reloaded" etc. at the top of the home feed.
                ClassTarget("Ua.O"),
                // LiveTVCard.
                ClassTarget("mb.q"),
            ),
        // AdView (`AdView.kt:52`). Renders the `NonNativeAd` feed item card
        // — a generic ad container, not the UFC sponsored Announcement.
        nonNativeAdRenderer = ClassTarget("k6.b"),
        // Announcement (`Announcement.kt:135`). This is the actual home-feed
        // renderer for the "UFC Freedom 250 / Proudly sponsored by Truth
        // Social / Learn More" banner — `Ja.O case 1` → `La.a.d`.
        homeAnnouncementRenderer = ClassTarget("La.a"),
        // Data-layer FeedItem filter (Compose-safe) — same fix as v1.27.1.
        // `L5.c.y(List, TimelineType)` builds the timeline list; elements are
        // `hd.a` with the FeedItemType in field `b`.
        feedItemMapper = ClassTarget("L5.c"),
        feedItemMapperMethod = "y",
        feedItemWrapper = ClassTarget("hd.a"),
        askPerplexityButton = ClassTarget("S8.E"),
        askPerplexityButtonMethod = "p",
        appBuildInfo = ClassTarget("ac.a"),
        // v1.27.0: SearchAIUseCase Hilt factory's newInstance() returns `g8.l`.
        // The class is a no-op holder, so the runtime invoke()-hook has
        // nothing to neutralize, but pointing the field at the real class
        // future-proofs against the FQN reappearing on a later build.
        searchAiUseCase = ClassTarget("g8.l"),
        premiumGateHelper = ClassTarget("E6.C"),
        premiumGateMethods =
            PremiumGateMethods(
                editsEnabled = "a",
                scheduleEnabled = "d",
                geofence = "e",
                editsVisible = "f",
                scheduleVisible = "h",
            ),
        // v1.27.0: TruthComposerViewModel is `ab.Z` per its Hilt _Factory;
        // publish/schedule entrypoint is `P1(boolean publish, Continuation)`.
        // Not read by any current hook but kept calibrated for future use.
        composerViewModel = ClassTarget("ab.Z"),
        composerScheduleClickMethod = "P1",
        navHandler = ClassTarget("Sb.s"),
        navHandlerNavigateMethod = "d",
        truthPlusUpsellRoute = ClassTarget("Ub.M\$a"),
        premiumFeatureRoadblockRoute = ClassTarget("Ub.B\$a"),
        feedClass = ClassTarget("com.truthsocial.core.data.models.feeds.Feed"),
        featuresClass = ClassTarget("com.truthsocial.core.data.models.Features"),
        // Preferences screen rewritten. Modern injector targets
        // `oa.k.p(Zd.f, …)` and appends an MTGA section. Legacy fields
        // below are unused under Modern; populated with real classes
        // so an accidental Legacy fallback doesn't `ClassNotFoundException`.
        preferencesInjector = PreferencesInjectorKind.Modern,
        preferencesBuilder = ClassTarget("oa.k"),
        preferencesBuilderMethod = "p",
        preferencesSection = ClassTarget("Zd.b"),
        preferencesTextRow = ClassTarget("Zd.d"),
        kotlinFunction0 = ClassTarget("De.a"),
        kotlinUnit = ClassTarget("pe.v"),
        modernPreferencesBuilder = ClassTarget("oa.k"),
        modernPreferencesBuilderMethod = "p",
        modernPreferencesRoot = ClassTarget("Zd.f"),
        modernPreferencesSection = ClassTarget("Zd.b"),
        modernPreferencesTextRow = ClassTarget("Zd.d"),
        modernKotlinFunction0 = ClassTarget("De.a"),
        resStringHelpCenter = 0x7f1202c4,
        resStringVersion = 0x7f1206bf,
    )

private val TargetsV1_27_1 =
    TargetSet(
        buildId =
            BuildId(
                versionName = "1.27.1",
                versionCode = 1260,
                baseApkSha256 = "e0dc0137dde710c770bbe83920271416cdd75c6bac6103e983950bad39281060",
            ),
        // DEX-audited (parallel verifier pass). Several v1.27.0-inherited
        // values turned out wrong: `I6.f` is now a synthetic Function2
        // lambda (the integrity interceptor moved to `K6.f`),
        // `retrofit2.OkHttpCall.u/G` no longer exist (the renamed letters
        // shifted to `n`/`y` because R8 re-hashed the class), and the
        // AppStateManagerImpl method assignments rotated by one slot.
        integrityInterceptor = ClassTarget("K6.f"),
        integrityInterceptMethod = "a",
        chainRequestField = "e",
        chainProceedMethod = "b",
        integrityChain = ClassTarget("xg.g"),
        okhttpRequest = ClassTarget("sg.D"),
        okhttpResponse = ClassTarget("sg.J"),
        feedIdMethod = null,
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "n",
        retrofitOkHttpCallRequestMethod = "y",
        feedsRepository = ClassTarget("R7.i"),
        appStateManager = ClassTarget("J6.a"),
        // Method assignments on AppStateManagerImpl rotated:
        //   `b(gd.h)V` = onReselect (was `c` on v1.27.0)
        //   `e(gd.h)V` = onSelect   (unchanged)
        //   `c(gd.h, I)V` = clearBadge (the "(Tab, int)" shape; was `d`)
        appStateTabSelectMethods = listOf("b", "e"),
        appStateClearBadgeMethod = "c",
        adQueueManager = ClassTarget("l7.e"),
        adQueueFetchMethod = null,
        adQueueInsertMethod = "c",
        adImpressionManager = ClassTarget("l7.b"),
        analyticsManager = ClassTarget("ac.c"),
        // The sidebar item renderer split out of `A6.I` (TruthNavDrawer)
        // into `A6.l` (NavigationItem.kt). `m` is the int-icon variant,
        // `n` is the ImageVector variant (used for Help Center via
        // Material's HelpOutline). Both leaves are void and take the
        // text resource id as one of the int args.
        sidebarItemRenderer = ClassTarget("A6.l"),
        sidebarItemMethods = listOf("m", "n"),
        accountDrawerScreen = ClassTarget("A6.I"),
        // Two gem methods (`f0`, `h0`) on the merged drawer class — list
        // both so the suppressor catches whichever variant runs.
        accountDrawerGemMethods = listOf("f0", "h0"),
        topAppBarFactory = ClassTarget("Wa.e"),
        topAppBarTruthPlusMethod = "i",
        navDrawerAvatar = ClassTarget("Jc.p"),
        navDrawerAvatarBadgeMethods = listOf("i", "j"),
        // Tab subclasses are `gd.b..g` (each extends `gd.h`). The route
        // descriptors at `Wb.<letter>` look superficially similar but
        // aren't tab instances — `isInstance` against them never matches
        // anything in the static list.
        bottomNavTabs = ClassTarget("gd.i"),
        bottomNavTabsListMethod = null,
        bottomNavTabsStaticFields = listOf("a", "b"),
        bottomNavAiTab = null,
        bottomNavAlertsTab = ClassTarget("gd.b"),
        bottomNavTabClasses =
            mapOf(
                "alerts" to ClassTarget("gd.b"),
                "discover" to ClassTarget("gd.c"),
                "groups" to ClassTarget("gd.d"),
                // toString labels diverged from the lowercase route ids:
                // `gd.e` = "Home" → "feeds"; `gd.f` = "Messages" → "chats".
                "feeds" to ClassTarget("gd.e"),
                "chats" to ClassTarget("gd.f"),
                "predictions" to ClassTarget("gd.g"),
            ),
        // R8 horizontal-class-merger collapsed the SwipeableRow file class
        // INTO `androidx.datastore.preferences.protobuf.n0` — the JVM
        // descriptor IS that protobuf path, not a jadx display artefact.
        // `h` is the `SwipeableRow.kt:51` public Composable entry; the
        // intuitive `t2.a` / `e` calibration matches a dagger lifecycle
        // helper and silently no-ops the dismiss-alert hook.
        swipeableRow = ClassTarget("androidx.datastore.preferences.protobuf.n0"),
        swipeableRowMethod = "h",
        alertsScreenPackagePrefix = "D8.",
        liveContentCarousel = ClassTarget("Ad.o"),
        liveContentCarouselMethod = "c",
        embeddedAnnouncement = ClassTarget("yd.d"),
        embeddedAnnouncementMethods = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"),
        extraLiveRenderers =
            listOf(
                ClassTarget("Wa.O"),
                ClassTarget("ob.q"),
            ),
        nonNativeAdRenderer = ClassTarget("l6.a"),
        homeAnnouncementRenderer = ClassTarget("Na.a"),
        // Data-layer FeedItem filter (Compose-safe) — replaces the legacy
        // noopAllComposables path for HideTopBannerAd / HideLiveCarousel on
        // this build. `k0.m.f(List, TimelineType)` builds the timeline list;
        // each element (`ld.a`) carries its FeedItemType in field `b`.
        feedItemMapper = ClassTarget("k0.m"),
        feedItemWrapper = ClassTarget("ld.a"),
        askPerplexityButton = ClassTarget("U8.E"),
        askPerplexityButtonMethod = "p",
        appBuildInfo = ClassTarget("ec.a"),
        searchAiUseCase = ClassTarget("i8.l"),
        premiumGateHelper = ClassTarget("G6.C"),
        premiumGateMethods =
            PremiumGateMethods(
                editsEnabled = "a",
                scheduleEnabled = "d",
                geofence = "e",
                editsVisible = "f",
                scheduleVisible = "h",
            ),
        // `Q1(boolean publish, Continuation) → Object` is the suspend
        // schedule-click; `P1(String)V` is a sibling that takes a draft id.
        composerViewModel = ClassTarget("cb.Z"),
        composerScheduleClickMethod = "Q1",
        navHandler = ClassTarget("Ub.s"),
        navHandlerNavigateMethod = "d",
        truthPlusUpsellRoute = ClassTarget("Wb.M\$a"),
        premiumFeatureRoadblockRoute = ClassTarget("Wb.B\$a"),
        feedClass = ClassTarget("com.truthsocial.core.data.models.feeds.Feed"),
        featuresClass = ClassTarget("com.truthsocial.core.data.models.Features"),
        preferencesInjector = PreferencesInjectorKind.Modern,
        // Legacy preferences fields are unused under Modern; populated
        // with real classes so an accidental Legacy fallback doesn't
        // `ClassNotFoundException`.
        preferencesBuilder = ClassTarget("qa.j"),
        preferencesBuilderMethod = "p",
        preferencesSection = ClassTarget("de.b"),
        preferencesTextRow = ClassTarget("de.d"),
        kotlinFunction0 = ClassTarget("He.a"),
        kotlinUnit = ClassTarget("te.v"),
        modernPreferencesBuilder = ClassTarget("qa.j"),
        modernPreferencesBuilderMethod = "p",
        modernPreferencesRoot = ClassTarget("de.f"),
        modernPreferencesSection = ClassTarget("de.b"),
        modernPreferencesTextRow = ClassTarget("de.d"),
        modernKotlinFunction0 = ClassTarget("He.a"),
        resStringHelpCenter = 0x7f1202c4,
        resStringVersion = 0x7f1206bf,
    )
