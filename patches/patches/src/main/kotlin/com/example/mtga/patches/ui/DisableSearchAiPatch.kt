package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByTypeOrNull

// Calibrated SearchAIUseCase is empty on some builds (R8 stripped `invoke`).
// Silently skip when the class or method is absent.

@Suppress("unused")
val disableSearchAiPatch =
    bytecodePatch(
        name = "Disable Search AI",
        description = "Neutralizes SearchAIUseCase invocations.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            val target =
                mutableClassByTypeOrNull(targets.searchAiUseCase.descriptor)
                    ?: return@execute

            for (method in target.methods.filter { it.name == "invoke" }) {
                val smali =
                    if (method.returnType == "V") {
                        "return-void"
                    } else {
                        """
                        const/4 v0, 0x0
                        return-object v0
                        """
                    }
                method.addInstructions(0, smali)
            }
        }
    }
