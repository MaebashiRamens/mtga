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

        // Per-build signature decides what we return — see [InsertShape].
        XposedBridge.hookAllMethods(
            clazz,
            targets.adQueueInsertMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    when (classifyInsertSignature(param)) {
                        InsertShape.SuspendListFetcher -> {
                            param.result = emptyList<Any>()
                        }
                        InsertShape.SuspendMergedFeed,
                        InsertShape.LegacyFeedListInsert,
                        -> {
                            param.args.getOrNull(2)?.let { param.result = it }
                        }
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
     * Three shapes of `AdQueueManager.c(...)` across Truth Social
     * releases. Returning empty is only safe for the list-fetcher
     * variant; the other two return the merged feed and have to be
     * handed arg[2] (the feed list) back unchanged.
     */
    private enum class InsertShape {
        /** Suspend `c(List, String, int, Continuation)` → just the ads. */
        SuspendListFetcher,

        /** Suspend `c(List, String, List feed, Continuation)` → merged feed. */
        SuspendMergedFeed,

        /** Non-suspend `c(_, _, feedList, …)` → merged feed. */
        LegacyFeedListInsert,
    }

    /**
     * `kotlin.coroutines.Continuation` is R8-renamed per build, so we
     * detect suspendiness structurally via [isContinuationLike] instead
     * of by class name. The third arg type then separates the v1.26.2+
     * list-fetcher (Int) from the v1.24.10 backport (List).
     */
    private fun classifyInsertSignature(param: XC_MethodHook.MethodHookParam): InsertShape {
        val args = param.args
        if (args.isEmpty()) return InsertShape.LegacyFeedListInsert
        val last = args.last()
        val suspend = last != null && isContinuationLike(last.javaClass)
        if (!suspend) return InsertShape.LegacyFeedListInsert
        val third = args.getOrNull(2)
        return if (third is Int) InsertShape.SuspendListFetcher else InsertShape.SuspendMergedFeed
    }

    private fun isContinuationLike(cls: Class<*>): Boolean {
        // Walk the class + interface graph for `resumeWith(Object)`.
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(cls)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            val hit =
                c.declaredMethods.any {
                    it.name == "resumeWith" && it.parameterCount == 1
                }
            if (hit) return true
            c.superclass?.let(queue::add)
            c.interfaces.forEach(queue::add)
        }
        return false
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
