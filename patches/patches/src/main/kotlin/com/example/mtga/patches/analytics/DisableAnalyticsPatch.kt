package com.example.mtga.patches.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// AppAnalyticsManager — every void-returning method becomes a no-op.
// Class is `ac.c` on v1.24.6 / v1.24.8.

@Suppress("unused")
val disableAnalyticsPatch =
    bytecodePatch(
        name = "Disable analytics",
        description = "Neutralizes AppAnalyticsManager — every void-returning method becomes return-void.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            mutableClassByType(targets.analyticsManager.descriptor)
                .methods
                .filter { it.returnType == "V" }
                .forEach { it.addInstructions(0, "return-void") }
        }
    }
