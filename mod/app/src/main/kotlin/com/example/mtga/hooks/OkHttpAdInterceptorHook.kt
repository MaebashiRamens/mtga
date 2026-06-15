package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Network-level ad blocking via Retrofit interception.
 *
 * R8 strips okhttp3.RealCall, so we hook retrofit2.OkHttpCall (preserved by
 * Retrofit) and short-circuit enqueue() / execute() when the URL matches a
 * blocked pattern. Response bodies are not modified here; that's
 * AdBlockHook's job at the data layer (AdQueueManager).
 */
class OkHttpAdInterceptorHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "OkHttpAdInterceptor"

    override fun hook(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(targets.retrofitOkHttpCall.name, classLoader)
        // `enqueue` is R8-renamed on v1.24.8+ (`l` / `u`); use the per-build
        // name from [TargetSet]. `execute` survives R8 on every build we've
        // seen because retrofit2 keeps it as part of the `retrofit2.Call`
        // public interface, so the literal name still works.
        val callback = AdBlockBeforeHook()
        XposedBridge.hookAllMethods(clazz, targets.retrofitOkHttpCallEnqueueMethod, callback)
        XposedBridge.hookAllMethods(clazz, "execute", callback)
        XposedBridge.log(
            "[$TAG] OkHttp hooked via ${clazz.name} (enqueue=${targets.retrofitOkHttpCallEnqueueMethod})",
        )
    }

    private inner class AdBlockBeforeHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val url = readRequestUrl(param.thisObject) ?: return
            if (BLOCKED_URL_PATTERNS.any { url.contains(it) }) {
                XposedBridge.log("[$TAG] Blocked ad request: $url")
                if (param.method.name == "execute") {
                    // Synchronous `Call.execute()` must return a non-null
                    // Response — `result = null` would NPE the host. Surface a
                    // network-style failure instead so the caller's existing
                    // error path handles it (the data-layer AdBlockHook still
                    // hides the ad either way). `execute` declares IOException.
                    param.throwable = java.io.IOException("MTGA: blocked ad request")
                } else {
                    // `enqueue(...)` returns void; suppressing it is enough.
                    param.result = null
                }
            }
        }

        /**
         * Walk Retrofit's OkHttpCall to its Request URL across R8 renames.
         * v1.24.8 keeps the source-form `rawCall`/`createRawCall` names;
         * v1.26.2+ R8-renames `createRawCall` to
         * [TargetSet.retrofitOkHttpCallRequestMethod] (typically `p` / `G`).
         * Try source names first, then the calibrated accessor.
         */
        private fun readRequestUrl(call: Any): String? {
            return try {
                // Source-name accessors first (v1.24.8 keeps them).
                val rawCall =
                    runCatching { XposedHelpers.getObjectField(call, "rawCall") }.getOrNull()
                        ?: runCatching { XposedHelpers.callMethod(call, "createRawCall") }.getOrNull()
                if (rawCall != null) {
                    val request = XposedHelpers.callMethod(rawCall, "request")
                    return XposedHelpers.callMethod(request, "url").toString()
                }
                // v1.26.2+: the request-getter method on OkHttpCall returns
                // the okhttp3.Request directly (no need to go through rawCall).
                val request =
                    runCatching {
                        XposedHelpers.callMethod(call, targets.retrofitOkHttpCallRequestMethod)
                    }.getOrNull() ?: return null
                // okhttp3 ProGuard keep rules preserve `Request.url()` as a
                // toString-able accessor on every build we've seen.
                runCatching { XposedHelpers.callMethod(request, "url").toString() }.getOrNull()
                    ?: runCatching {
                        // Last-ditch: the typed field on Request (`og.E.a`
                        // on v1.27.0 is the URL).
                        XposedHelpers.getObjectField(request, "a")?.toString()
                    }.getOrNull()
            } catch (_: Throwable) {
                null
            }
        }
    }

    companion object {
        private val BLOCKED_URL_PATTERNS = listOf("/truth/ads")
    }
}
