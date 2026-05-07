package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetSet
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Blocks ads at the data layer:
 *  - AdQueueManager.b(): return null (no ad fetched)
 *  - AdQueueManager.c(): return the input feed unchanged (no ad inserted)
 *  - AdImpressionManager: every void method becomes no-op
 *  - TrackAdImpression / TrackVisibleItems use cases: invoke() → no-op
 */
class AdBlockHook(
    targets: TargetSet,
) : BaseHook(targets) {
    override val name = "AdBlock"

    override fun hook(classLoader: ClassLoader) {
        hookAdQueueManager(classLoader)
        hookAdImpressionManager(classLoader)
        hookTrackUseCases(classLoader)
    }

    private fun hookAdQueueManager(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(targets.adQueueManager.name, classLoader)

        // b() = fetchAd → null
        XposedBridge.hookAllMethods(
            clazz,
            "b",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? = null
            },
        )
        // c(_, _, feedList, ...) = insertAdsIntoFeed → return feed unchanged
        XposedBridge.hookAllMethods(
            clazz,
            "c",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args.getOrNull(2)?.let { param.result = it }
                }
            },
        )
        XposedBridge.log("[$TAG] AdQueueManager hooked (${clazz.name})")
    }

    private fun hookAdImpressionManager(classLoader: ClassLoader) {
        val clazz =
            try {
                XposedHelpers.findClass(targets.adImpressionManager.name, classLoader)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] AdImpressionManager not found: ${t.message}")
                return
            }
        clazz.declaredMethods
            .filter { it.returnType == Void.TYPE }
            .forEach { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
        XposedBridge.log("[$TAG] AdImpressionManager hooked (${clazz.name})")
    }

    private fun hookTrackUseCases(classLoader: ClassLoader) {
        // Use-case classes use full package paths that survive R8 because
        // Hilt injects them by class name.
        val useCases =
            listOf(
                "com.truthsocial.app.domain.usecase.ads.TrackAdImpression",
                "com.truthsocial.app.domain.usecase.ads.TrackVisibleItems",
            )
        for (className in useCases) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                XposedBridge.hookAllMethods(clazz, "invoke", XC_MethodReplacement.DO_NOTHING)
                XposedBridge.log("[$TAG] $className hooked")
            } catch (_: Throwable) {
                // class may have been removed in newer builds
            }
        }
    }
}
