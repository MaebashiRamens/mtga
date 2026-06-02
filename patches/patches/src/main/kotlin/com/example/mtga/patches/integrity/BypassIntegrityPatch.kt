package com.example.mtga.patches.integrity

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// p1 is declared `Lwe/s;` (Interceptor.Chain) but the request field only
// exists on the concrete `LBe/h;`; without the check-cast the verifier
// rejects the iget with "Reference: we.s not instance of Be.h".

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
                        move-object v0, p1
                        check-cast v0, LBe/h;
                        iget-object v1, v0, LBe/h;->${targets.chainRequestField}:Lwe/B;
                        invoke-virtual {v0, v1}, LBe/h;->${targets.chainProceedMethod}(Lwe/B;)Lwe/H;
                        move-result-object v0
                        return-object v0
                        """,
                    )
                }
        }
    }
