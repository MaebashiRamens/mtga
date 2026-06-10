package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetResolver
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
        hookFirebaseClass(
            classLoader,
            "com.google.firebase.crashlytics.FirebaseCrashlytics",
            setOf("log", "recordException", "sendUnsentReports", "setUserId", "setCustomKey", "setCrashlyticsCollectionEnabled"),
        )
        hookFirebaseClass(
            classLoader,
            "com.google.firebase.analytics.FirebaseAnalytics",
            setOf("logEvent", "setUserProperty", "setUserId", "setAnalyticsCollectionEnabled"),
        )
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
