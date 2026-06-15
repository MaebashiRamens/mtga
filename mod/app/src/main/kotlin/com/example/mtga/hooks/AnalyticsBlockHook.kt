package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Disables analytics + telemetry:
 *  - AppAnalyticsManager: every void method becomes no-op
 *  - Firebase Crashlytics + Analytics: known logging methods become no-op
 */
class AnalyticsBlockHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "AnalyticsBlock"

    override fun hook(classLoader: ClassLoader) {
        hookAppAnalytics(classLoader)
        // No-op the logging surface only. We deliberately do NOT no-op the
        // `set*CollectionEnabled` setters: doing so also swallows the app's
        // own attempts to DISABLE collection, leaving it on. Instead we force
        // collection off on each singleton via [forceCollectionDisabled].
        hookFirebaseClass(
            classLoader,
            "com.google.firebase.crashlytics.FirebaseCrashlytics",
            setOf("log", "recordException", "sendUnsentReports", "setUserId", "setCustomKey"),
        )
        hookFirebaseClass(
            classLoader,
            "com.google.firebase.analytics.FirebaseAnalytics",
            setOf("logEvent", "setUserProperty", "setUserId"),
        )
        // Automatic events (screen_view / session_start / first_open) and the
        // auto crash handler are installed at SDK init and bypass the logging
        // no-ops above. Force collection off on each singleton as it's handed
        // out so the toggle actually delivers its headline guarantee.
        forceCollectionDisabled(
            classLoader,
            "com.google.firebase.analytics.FirebaseAnalytics",
            "setAnalyticsCollectionEnabled",
        )
        forceCollectionDisabled(
            classLoader,
            "com.google.firebase.crashlytics.FirebaseCrashlytics",
            "setCrashlyticsCollectionEnabled",
        )
    }

    /**
     * Hook `getInstance(...)` on a Firebase entry-point class and disable
     * collection on the returned singleton. Setting it explicitly to `false`
     * (rather than no-op'ing the setter) stops the SDK's automatic event /
     * crash collection that the logging no-ops can't reach. All-overloads hook
     * so the `Context`-taking and no-arg `getInstance` variants are both
     * covered. Fully guarded — a missing class or method is non-critical.
     */
    private fun forceCollectionDisabled(
        classLoader: ClassLoader,
        className: String,
        setterName: String,
    ) {
        runCatching {
            val clazz = XposedHelpers.findClass(className, classLoader)
            XposedBridge.hookAllMethods(
                clazz,
                "getInstance",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val instance = param.result ?: return
                        runCatching { XposedHelpers.callMethod(instance, setterName, false) }
                    }
                },
            )
        }.onFailure { XposedBridge.log("[$TAG] forceCollectionDisabled($className) skipped: ${it.message}") }
    }

    private fun hookAppAnalytics(classLoader: ClassLoader) {
        val clazz =
            try {
                XposedHelpers.findClass(targets.analyticsManager.name, classLoader)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] AppAnalyticsManager not found: ${t.message}")
                return
            }
        clazz.declaredMethods
            .filter { it.returnType == Void.TYPE }
            .forEach { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
        XposedBridge.log("[$TAG] AppAnalyticsManager hooked (${clazz.name})")
    }

    private fun hookFirebaseClass(
        classLoader: ClassLoader,
        className: String,
        methodNames: Set<String>,
    ) {
        try {
            val clazz = XposedHelpers.findClass(className, classLoader)
            clazz.declaredMethods
                .filter { it.name in methodNames }
                .forEach { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
        } catch (_: Throwable) {
            // Firebase may not be initialized in some builds; non-critical.
        }
    }
}
