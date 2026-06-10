package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Bypass Play Integrity API checks.
 *
 * Truth Social uses Play Integrity for: CreateAccount, Like, Status,
 * ChatMessage, ReTruth, Reaction. An OkHttp Interceptor calls Google's
 * IntegrityManager and adds an `x-tru-assertion` header to the outgoing
 * request.
 *
 * Make the interceptor pass-through: call `chain.proceed(chain.request)`
 * directly without building or attaching the integrity token. The server
 * may or may not accept the request — depends on per-action server policy.
 *
 * Truth Social does NOT implement: root detection, Xposed/LSPosed detection,
 * emulator detection, or signature verification (verified via decompiled
 * source scan).
 */
class IntegrityBypassHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "IntegrityBypass"

    override fun hook(classLoader: ClassLoader) {
        val interceptorClass = XposedHelpers.findClass(targets.integrityInterceptor.name, classLoader)

        XposedBridge.hookAllMethods(
            interceptorClass,
            targets.integrityInterceptMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val chain = param.args.getOrNull(0) ?: return
                    try {
                        val request = XposedHelpers.getObjectField(chain, targets.chainRequestField)
                        param.result = XposedHelpers.callMethod(chain, targets.chainProceedMethod, request)
                    } catch (t: Throwable) {
                        XposedBridge.log("[$TAG] Integrity passthrough failed: ${t.message}")
                    }
                }
            },
        )
        XposedBridge.log("[$TAG] IntegrityInterceptor (${interceptorClass.name}.${targets.integrityInterceptMethod}) bypassed")
    }
}
