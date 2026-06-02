package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// resStringHelpCenter is build-pinned to Targets.latest. The resource id can
// shift across versions; this .rvp will silently skip the wrong sidebar row
// on a calibrated older build whose id differs.

@Suppress("unused")
val hideHelpCenterPatch =
    bytecodePatch(
        name = "Hide Help Center sidebar item",
        description = "Suppresses the Help Center row in the account drawer sidebar.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            val helpCenterId = targets.resStringHelpCenter
            mutableClassByType(targets.sidebarItemRenderer.descriptor)
                .methodsNamed("j")
                .forEach { method ->
                    method.addInstructionsWithLabels(
                        0,
                        """
                        move/from16 v0, p2
                        const v1, $helpCenterId
                        if-ne v0, v1, :L_continue
                        return-void
                        :L_continue
                        nop
                        """,
                    )
                }
        }
    }
