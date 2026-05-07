package com.example.mtga.patches.integrity

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// Truth Social's Play Integrity OkHttp interceptor. The stock `intercept(Chain)`
// body builds an integrity assertion and attaches an `x-tru-assertion` header;
// we short-circuit to a straight `chain.proceed(chain.request)`.
//
// Field name + proceed-method name come from TargetSet (chainRequestField /
// chainProceedMethod). The Chain owner type (`LBe/h;`) and Request type
// (`LC5431B;`) are still inlined in the smali below — both v1.24.6 and v1.24.8
// share them, so promoting them to TargetSet hasn't been necessary yet. If a
// future build moves them, surface them on TargetSet and substitute here.

@Suppress("unused")
val bypassIntegrityPatch =
    bytecodePatch(
        name = "Bypass Play Integrity",
        description = "Skips Play Integrity assertion injection — chain proceeds with the original request.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            mutableClassByType(targets.integrityInterceptor.descriptor)
                .methodsNamed(targets.integrityInterceptMethod)
                .forEach { method ->
                    method.addInstructions(
                        0,
                        """
                        # Equivalent to: return chain.proceed(chain.request())
                        # Chain (Be.h) holds the current request in field `e` and
                        # exposes `b(Request) → Response` as the proceed method.
                        # If a future build relocates these, surface the chain class
                        # via TargetSet and substitute via a string template.
                        iget-object v0, p1, LBe/h;->${targets.chainRequestField}:LC5431B;
                        invoke-virtual {p1, v0}, LBe/h;->${targets.chainProceedMethod}(LC5431B;)Ljava/lang/Object;
                        move-result-object v0
                        return-object v0
                        """,
                    )
                }
        }
    }
