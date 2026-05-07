package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetSet
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Network-level ad blocking via Retrofit interception.
 *
 * R8 strips okhttp3.RealCall, so we hook retrofit2.OkHttpCall (preserved by
 * Retrofit) and short-circuit enqueue() / execute() when the URL matches a
 * blocked pattern. We don't modify response bodies here — that's handled by
 * AdBlockHook at the data layer (AdQueueManager).
 */
class OkHttpAdInterceptorHook(
    targets: TargetSet,
) : BaseHook(targets) {
    override val name = "OkHttpAdInterceptor"

    override fun hook(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(targets.retrofitOkHttpCall.name, classLoader)
        XposedBridge.hookAllMethods(clazz, "enqueue", AdBlockBeforeHook)
        XposedBridge.hookAllMethods(clazz, "execute", AdBlockBeforeHook)
        XposedBridge.log("[$TAG] OkHttp hooked via ${clazz.name}")
    }

    private object AdBlockBeforeHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val url = readRequestUrl(param.thisObject) ?: return
            if (BLOCKED_URL_PATTERNS.any { url.contains(it) }) {
                XposedBridge.log("[$TAG] Blocked ad request: $url")
                param.result = null
            }
        }

        private fun readRequestUrl(call: Any): String? {
            return try {
                val rawCall =
                    runCatching { XposedHelpers.getObjectField(call, "rawCall") }.getOrNull()
                        ?: runCatching { XposedHelpers.callMethod(call, "createRawCall") }.getOrNull()
                        ?: return null
                val request = XposedHelpers.callMethod(rawCall, "request")
                XposedHelpers.callMethod(request, "url").toString()
            } catch (_: Throwable) {
                null
            }
        }
    }

    companion object {
        private val BLOCKED_URL_PATTERNS = listOf("/truth/ads")
    }
}
