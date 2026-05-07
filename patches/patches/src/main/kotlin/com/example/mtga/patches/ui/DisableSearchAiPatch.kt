package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByTypeOrNull

// SearchAIUseCase. Class name is full-package because Hilt injects it by FQN
// — R8 keeps it for that reason. The use case's `invoke()` typically returns
// a Single<...> wrapper; we short-circuit to null so subscribers see no
// result. Skips silently if the class has been removed.

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
