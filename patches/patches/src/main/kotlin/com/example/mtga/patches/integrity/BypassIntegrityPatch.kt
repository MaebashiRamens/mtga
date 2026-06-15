package com.example.mtga.patches.integrity

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// p1 is declared as the `Interceptor.Chain` interface, but the request field
// only exists on the concrete chain impl (Be.h ≤1.26.1, og.g/tg.g/xg.g on
// newer builds). Without the check-cast the verifier rejects the iget. The
// chain class and the okhttp Request/Response descriptors all drift per build,
// so read them from the resolved TargetSet rather than hardcoding.

@Suppress("unused")
val bypassIntegrityPatch =
    bytecodePatch(
        name = "Bypass Play Integrity",
        description = "Skips Play Integrity assertion injection — chain proceeds with the original request.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            val chain = targets.integrityChain.descriptor
            val request = targets.okhttpRequest.descriptor
            val response = targets.okhttpResponse.descriptor
            mutableClassByType(targets.integrityInterceptor.descriptor)
                .methodsNamed(targets.integrityInterceptMethod)
                .forEach { method ->
                    method.addInstructions(
                        0,
                        """
                        move-object v0, p1
                        check-cast v0, $chain
                        iget-object v1, v0, $chain->${targets.chainRequestField}:$request
                        invoke-virtual {v0, v1}, $chain->${targets.chainProceedMethod}($request)$response
                        move-result-object v0
                        return-object v0
                        """,
                    )
                }
        }
    }
