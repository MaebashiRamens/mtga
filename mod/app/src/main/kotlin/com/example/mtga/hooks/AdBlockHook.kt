package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Block ads at the data layer.
 *
 * v1.26.1 AdQueueManager:
 *   - `b()` = fetchAd → null (no ad fetched).
 *   - `c(_, _, feedList, ...)` = insertAdsIntoFeed → return feed unchanged.
 *
 * v1.26.2+ AdQueueManager (refactored):
 *   - `b()` no longer exists.
 *   - `c(adIndexes, zone, maxListSize, Continuation)` is a suspend
 *     list-fetcher returning `List<? extends ke.j>`. We return empty list.
 *   - `e(timelineId, adIndexes, zone, maxListSize, indexOffset)` is the
 *     void side-effecting writer. We no-op it.
 *
 * Other:
 *  - AdImpressionManager: every void method becomes no-op.
 *  - TrackAdImpression / TrackVisibleItems use cases: invoke() → no-op.
 */
class AdBlockHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "AdBlock"

    override fun hook(classLoader: ClassLoader) {
        hookAdQueueManager(classLoader)
        hookAdImpressionManager(classLoader)
        hookTrackUseCases(classLoader)
    }

    private fun hookAdQueueManager(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(targets.adQueueManager.name, classLoader)

        // Optional fetchAd → null. v1.26.2+ removed this method entirely.
        targets.adQueueFetchMethod?.let { fetchName ->
            XposedBridge.hookAllMethods(
                clazz,
                fetchName,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? = null
                },
            )
        }

        // Insert/list-ads method. Per-build signature decides what we return.
        XposedBridge.hookAllMethods(
            clazz,
            targets.adQueueInsertMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (looksLikeV1_26_2InsertSignature(param)) {
                        // suspend Object c(List<Integer>, String, int, Continuation):
                        // resolve to an empty ads list. The caller awaits
                        // the Continuation result, so setting the method
                        // result short-circuits the body.
                        param.result = emptyList<Any>()
                    } else {
                        // Legacy v1.26.1 / v1.24.x: 3rd arg is the feed list;
                        // return it unchanged so no ads get spliced in.
                        param.args.getOrNull(2)?.let { param.result = it }
                    }
                }
            },
        )

        // v1.26.2+: e(timelineId, adIndexes, zone, maxListSize, indexOffset)
        // is the void writer. No-op. Only fires when the method exists;
        // legacy builds don't have it.
        runCatching {
            clazz.declaredMethods
                .filter { it.name == "e" && it.returnType == Void.TYPE && it.parameterTypes.size == 5 }
                .forEach { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
        }

        XposedBridge.log(
            "[$TAG] AdQueueManager hooked (${clazz.name}, " +
                "fetch=${targets.adQueueFetchMethod}, insert=${targets.adQueueInsertMethod})",
        )
    }

    /**
     * The v1.26.2+ refactor of `c()` is a suspend function. Kotlin's
     * compiler surfaces that as a Java method whose last parameter is a
     * `kotlin.coroutines.Continuation` (R8 renames the class but keeps
     * the `kotlin.coroutines` package name). Use that signal instead of a
     * per-version method-shape table.
     */
    private fun looksLikeV1_26_2InsertSignature(param: XC_MethodHook.MethodHookParam): Boolean {
        val args = param.args
        if (args.isEmpty()) return false
        // Suspend functions get a Continuation as their last extra param.
        // Its R8-renamed class lives under "kotlin.coroutines.*"; the
        // package prefix survives because the Kotlin runtime keeps it.
        val last = args.last() ?: return false
        return last.javaClass.interfaces.any { it.name == "kotlin.coroutines.Continuation" } ||
            generateSequence(last.javaClass as Class<*>?) { it.superclass }
                .any { it.name == "kotlin.coroutines.jvm.internal.ContinuationImpl" }
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
        // Use-case classes keep their full package paths through R8 because
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
                // class may be removed in newer builds
            }
        }
    }
}
