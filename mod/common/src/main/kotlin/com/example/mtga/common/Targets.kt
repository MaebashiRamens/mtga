package com.example.mtga.common

/**
 * Hook / patch coordinates for Truth Social, keyed by app version.
 *
 * R8 minification renames classes/methods on every build, so the names that
 * are right for v1.24.8 are very likely wrong for v1.25.0. This file holds
 * one [TargetSet] per known-tested version. At hook init we look up the
 * current versionCode and pick the matching set; if none matches, the
 * runtime hooks bail out instead of binding to wrong symbols.
 *
 * ## Calibration workflow (adding a new version)
 *
 * 1. Drop the apkmirror `.apkm` bundle into the project root.
 * 2. `unzip` it; record `sha256sum base.apk` (goes into [BuildId]).
 * 3. `nix develop --command jadx --no-res -d /tmp/jadx_<v> /tmp/<v>/base.apk`.
 *    The only resource id we need ([resStringHelpCenter]) can be read with
 *    `aapt2 dump resources` instead — `--no-res` is fine for class discovery.
 * 4. For each [TargetSet] field, follow its `HOW TO LOCATE` note to grep the
 *    new tree. Most fields stay identical between minor releases.
 *
 *    **JADX file-name vs JVM class name**: jadx renames class files when the
 *    original would collide on a case-insensitive filesystem (`e.java` and
 *    `E.java`). The DEX/JVM class name is unchanged — read the
 *    `/* JADX INFO: renamed from: <pkg>.<name> */` marker near the top of
 *    the renamed file. Always feed the *original* DEX name into [ClassTarget],
 *    never jadx's display name (`C1744e`, etc.).
 *
 * 5. Verify each method-name field by inspecting the located class — R8
 *    renames method names too and they can drift independently of their
 *    owning class.
 *
 * 6. Append a new `TargetsV<X_Y_Z>` constant at the bottom and register it
 *    in [knownVersions].
 *
 * 7. `./gradlew :mod:app:assembleDebug` and `nix run .#build-patches`.
 *    Both must succeed before deploying.
 */
object Targets {
    const val PACKAGE = "com.truthsocial.android.app"

    val knownVersions: List<TargetSet> = listOf(TargetsV1_26_1, TargetsV1_24_8, TargetsV1_24_6)

    val knownVersionNames: Array<String>
        get() = knownVersions.map { it.buildId.versionName }.toTypedArray()

    fun forVersionCode(versionCode: Int): TargetSet? = knownVersions.firstOrNull { it.buildId.versionCode == versionCode }

    fun forVersionName(versionName: String): TargetSet? = knownVersions.firstOrNull { it.buildId.versionName == versionName }

    val latest: TargetSet get() = knownVersions.first()
}

/**
 * One TargetSet = a complete map from "thing we want to hook" to "obfuscated
 * name in this specific Truth Social build". Each field carries a HOW TO
 * LOCATE note so future calibration sessions can re-find the symbol on a
 * brand-new APK without re-discovering the anchor strings.
 */
data class TargetSet(
    val buildId: BuildId,
    // ---------------------------- Network ------------------------------------
    /**
     * OkHttp interceptor that injects the Play Integrity assertion.
     *
     * HOW TO LOCATE: grep for the literal `"x-tru-assertion"` (the header it
     * adds). One class hits — confirm by reading its `intercept(chain)` body.
     */
    val integrityInterceptor: ClassTarget,
    /** HOW TO LOCATE: the only method on [integrityInterceptor] returning Object/Response. R8 single-letter (typically `a`). */
    val integrityInterceptMethod: String,
    /**
     * Field on the OkHttp `RealInterceptorChain` impl holding the current Request.
     *
     * HOW TO LOCATE: in the chain class (`Be.h`-shaped — list of interceptors
     * + int index), the Request field is the only one whose type is the
     * obfuscated `okhttp3.Request`. R8 names it by alphabetical position
     * (5th field → `e`).
     */
    val chainRequestField: String,
    /** HOW TO LOCATE: in the chain class, the method whose body advances the interceptor index and returns a Response. Usually `b`. */
    val chainProceedMethod: String,
    /**
     * Retrofit's `OkHttpCall`. Retrofit's ProGuard rules keep the original
     * package + class name on every build. Hard-coded as a sanity-check.
     */
    val retrofitOkHttpCall: ClassTarget,
    /**
     * R8-renamed `enqueue(Callback)` on [retrofitOkHttpCall]. Patched by
     * [BlockOkHttpAdsPatch] to short-circuit `/truth/ads` requests.
     *
     * HOW TO LOCATE: the only public void method on `OkHttpCall` taking a
     * single argument typed as `retrofit2.Callback` (itself R8-renamed —
     * look for the parameter type that has a single non-default method
     * named `onResponse` or similar). Usually single-letter `l`.
     */
    val retrofitOkHttpCallEnqueueMethod: String,
    /**
     * R8-renamed `createRawCall()` analog — builds the `okhttp3.Request`
     * from `requestFactory.create(args)`. Returns `we.B` (Request).
     *
     * HOW TO LOCATE: the only public method on `OkHttpCall` whose return
     * type is the Request type (`Lwe/B;` shape — a class with a single-
     * field `<init>(Builder)`). Usually `declared-synchronized` and
     * usually named `p`.
     */
    val retrofitOkHttpCallRequestMethod: String,
    // ---------------------------- Repositories -------------------------------
    /**
     * `FeedsRepositoryImpl` — methods that take or return `List<Feed>` where
     * `Feed = com.truthsocial.app.data.models.feeds.Feed`.
     *
     * HOW TO LOCATE: grep for that `Feed` literal; narrow to a class with
     * several `List<Feed>` methods, dependency-injected.
     */
    val feedsRepository: ClassTarget,
    /**
     * `AppStateManagerImpl` — bottom-bar nav + badge counts.
     *
     * HOW TO LOCATE: a class with `c(menuItem)`, `e(menuItem)` and `g(menuItem, int)`
     * where `g(_, 0)` is called to clear the alerts badge.
     */
    val appStateManager: ClassTarget,
    // ---------------------------- Ads / analytics ----------------------------
    /**
     * `AdQueueManager` — `b()` (fetchAd) returning Object and `c(...)`
     * (insertAdsIntoFeed) taking a feed list.
     *
     * HOW TO LOCATE: grep `/api/v5/truth/ads`; AdQueueManager is DI'd from
     * the AdsApi consumer. Kotlin metadata explicitly names it
     * `com.truthsocial.app.data.api.service.ads.AdQueueManager`.
     */
    val adQueueManager: ClassTarget,
    /** Sibling of [adQueueManager]. Metadata names it `AdImpressionManager`. */
    val adImpressionManager: ClassTarget,
    /** HOW TO LOCATE: grep `AppAnalyticsManager` in metadata blocks, or look near Firebase `logEvent` calls. */
    val analyticsManager: ClassTarget,
    // ---------------------------- UI / Compose -------------------------------
    /**
     * Sidebar item renderer — method `j(modifier, icon, textResId, hasDivider, onClick, …)`.
     *
     * HOW TO LOCATE: grep [resStringHelpCenter] usage — only the sidebar
     * item renderer consumes it.
     */
    val sidebarItemRenderer: ClassTarget,
    /**
     * Account drawer screen — gem button + Truth Gems banner methods.
     *
     * HOW TO LOCATE: usually same package as [sidebarItemRenderer]. Grep for
     * `Truth Gems` literal or the Composable whose tree includes the sidebar.
     */
    val accountDrawerScreen: ClassTarget,
    /**
     * TopAppBar action factory — method `i()` renders the TRUTH+ upsell button.
     *
     * HOW TO LOCATE: grep for navigation to the Truth+ subscription route
     * (`"truth-plus-modal-bottom-sheet"`) within a top-app-bar Composable
     * factory. The `i()` method is a small `@Composable` lambda.
     */
    val topAppBarFactory: ClassTarget,
    /**
     * `NavDrawerAvatar` — gem badge `k()` (default zero) and `m(user)` (count).
     *
     * HOW TO LOCATE: jadx displays the file as `kotlin.AbstractC1695B` due to
     * a case-insensitive filesystem rename. Grep for `NavDrawerAvatarKt` in
     * metadata, then read the `JADX INFO: renamed from: <pkg>.B` marker for
     * the actual JVM name.
     */
    val navDrawerAvatar: ClassTarget,
    /**
     * Bottom navigation tabs container — `a()` returns `List<Tab>`.
     *
     * HOW TO LOCATE: grep bottom-nav route literals (`"home"`, `"alerts"`,
     * `"discover"`) within a class whose method returns a list.
     */
    val bottomNavTabs: ClassTarget,
    /**
     * Bottom-nav AI-tab subclass.
     *
     * HOW TO LOCATE: in the Tab base class (sibling of [bottomNavTabs]),
     * the AI tab references "AI" / "Truth Search". Inner classes encode as
     * `Outer$Inner` for [ClassTarget].
     */
    val bottomNavAiTab: ClassTarget,
    /** HOW TO LOCATE: same Tab base as [bottomNavAiTab]; the Alerts tab's route is `"alerts"`. */
    val bottomNavAlertsTab: ClassTarget,
    /**
     * SwipeableRow Composable — `j(modifier, swipeToStartAction, swipeToEndAction, state, content, …)`.
     *
     * HOW TO LOCATE: grep `SwipeableRow` in Kotlin metadata.
     */
    val swipeableRow: ClassTarget,
    /**
     * Truth Search AI use case. **NOT R8-renamed** — Hilt injects by FQN so
     * the original `com.truthsocial.app.domain.usecase.ai.SearchAIUseCase`
     * survives. Stable across all builds.
     */
    val searchAiUseCase: ClassTarget,
    /**
     * Premium-feature gate helper — static functions on `TruthSocialUser`:
     *
     *   a(user) → editsEnabled
     *   c(user) → scheduleEnabled
     *   d(user) → smsCountry == "US" geofence
     *   e(user) → editsVisible && d(user)
     *   g(user) → scheduleVisible && d(user)
     *
     * HOW TO LOCATE: grep `smsCountry` literal — the geofence helper is the
     * only consumer outside the data model itself.
     */
    val premiumGateHelper: ClassTarget,
    /**
     * Truth Compose post-action ViewModel — wraps the Schedule click.
     *
     * HOW TO LOCATE: grep Kotlin metadata for `TruthComposeViewModel` or
     * `composer` package paths.
     */
    val composerViewModel: ClassTarget,
    /**
     * ViewModel method called by the Schedule button — branches into upsell.
     *
     * HOW TO LOCATE: in [composerViewModel] metadata, the source parameter
     * list mentions `publish: Boolean`; the matching R8-renamed name is in
     * the metadata's d2 array.
     */
    val composerScheduleClickMethod: String,
    /**
     * NavHandler — instance method for `navigate(Route, options)`.
     *
     * HOW TO LOCATE: grep `"truth-plus-modal-bottom-sheet"` route literal —
     * the call site is `navHandler.<navigate>(route, options)`.
     */
    val navHandler: ClassTarget,
    /** HOW TO LOCATE: from the [navHandler] call-site grep, read the method name. R8 single-letter, usually `d`. */
    val navHandlerNavigateMethod: String,
    /**
     * Route subclass for `truth-plus-modal-bottom-sheet`. Inner classes
     * encode as `Outer$a` for [ClassTarget].
     */
    val truthPlusUpsellRoute: ClassTarget,
    /** HOW TO LOCATE: grep `"premium-feature-roadblock-dialog"` literal. */
    val premiumFeatureRoadblockRoute: ClassTarget,
    // ---------------------------- Preferences screen -------------------------
    /**
     * `PreferencesScreen` builder file class — appends sections to the prefs
     * root.
     *
     * HOW TO LOCATE: grep `"preferences/all"` (the route) → find the screen
     * Composable → trace to the helper. The helper is a Kotlin file class
     * with `abstract` modifier (a static-only utility).
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
     * Section type — 2 SharedPreferences + String<title> + boolean +
     * ArrayList<items>.
     *
     * The hook uses **type-based field discovery**, so individual field
     * names within this class don't need to be tracked separately.
     */
    val preferencesSection: ClassTarget,
    /** Clickable text row — same package as [preferencesSection], wider field set + `Mc.a` click callback. Type-based field discovery. */
    val preferencesTextRow: ClassTarget,
    /**
     * R8 rename of `kotlin.jvm.functions.Function0`. The `Mc` package
     * contains all `Function0..22` renames; `a` is reliably Function0
     * (no-arg).
     */
    val kotlinFunction0: ClassTarget,
    /**
     * R8 rename of `kotlin.Unit` — singleton field whose `toString` returns
     * `"kotlin.Unit"`, lives in the `yc` Kotlin-stdlib runtime package.
     */
    val kotlinUnit: ClassTarget,
    // ---------------------------- Resources ----------------------------------
    /**
     * `R.string.help_center` numeric id. Resource ids are stable across an
     * R8 minify but can shift between releases when other resources are added.
     *
     * HOW TO LOCATE: grep `R.java` for `help_center`, or
     * `aapt2 dump resources base.apk | grep string/help_center`.
     */
    val resStringHelpCenter: Int,
)

/** An obfuscated class name. No fallbacks: a wrong name should fail loudly. */
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

// v1.26.1 R8 hashing is mostly stable against v1.24.8 — the obfuscated names
// for hooks/patches are identical except:
//   - searchAiUseCase: the FQN `com.truthsocial.app.domain.usecase.ai.SearchAIUseCase`
//     no longer survives R8; the class is now renamed to `x8.l` (the use case
//     body was emptied to a no-op holder, so DisableSearchAiPatch becomes a
//     defensive no-op, and the runtime hook silently fails — Truth Search AI
//     label blanking still works via `blankStringResource`).
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
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "l",
        retrofitOkHttpCallRequestMethod = "p",
        feedsRepository = ClassTarget("g8.h"),
        appStateManager = ClassTarget("O6.b"),
        adQueueManager = ClassTarget("v7.d"),
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
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "l",
        retrofitOkHttpCallRequestMethod = "p",
        feedsRepository = ClassTarget("g8.h"),
        appStateManager = ClassTarget("O6.b"),
        adQueueManager = ClassTarget("v7.d"),
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
        searchAiUseCase = ClassTarget("com.truthsocial.app.domain.usecase.ai.SearchAIUseCase"),
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
    )

// v1.24.6 obfuscated names are identical to v1.24.8 — R8 hashing was stable
// between these releases. Registered separately so `forVersionCode` matches
// the running APK exactly and any future drift is caught loudly. Not
// deploy-tested (the rooted AVD has v1.24.8 installed and Android does not
// allow downgrading); verified at compile time and via jadx symbol equivalence.
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
        retrofitOkHttpCall = ClassTarget("retrofit2.OkHttpCall"),
        retrofitOkHttpCallEnqueueMethod = "l",
        retrofitOkHttpCallRequestMethod = "p",
        feedsRepository = ClassTarget("g8.h"),
        appStateManager = ClassTarget("O6.b"),
        adQueueManager = ClassTarget("v7.d"),
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
        searchAiUseCase = ClassTarget("com.truthsocial.app.domain.usecase.ai.SearchAIUseCase"),
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
    )
