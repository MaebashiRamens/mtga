package com.example.mtga.patches.premium

import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

@Suppress("unused")
val blockTruthPlusUpsellPatch =
    bytecodePatch(
        name = "Block Truth+ upsell",
        description = "Drops navigation to the Truth+ upsell sheet and the per-feature roadblock dialog.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            mutableClassByType(targets.navHandler.descriptor)
                .methodsNamed(targets.navHandlerNavigateMethod)
                .forEach { method ->
                    method.addInstructionsWithLabels(
                        0,
                        """
                        instance-of v0, p1, ${targets.truthPlusUpsellRoute.descriptor}
                        if-nez v0, :L_block
                        instance-of v0, p1, ${targets.premiumFeatureRoadblockRoute.descriptor}
                        if-eqz v0, :L_continue
                        :L_block
                        return-void
                        :L_continue
                        nop
                        """,
                    )
                }
        }
    }
