package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// TopAppBar TRUTH+ upsell button — `i()` Composable. Replacing the body
// with `return-void` makes the button vanish.

@Suppress("unused")
val hideTruthPlusButtonPatch =
    bytecodePatch(
        name = "Hide TRUTH+ button",
        description = "Removes the TRUTH+ upsell button from the top app bar.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            mutableClassByType(targets.topAppBarFactory.descriptor)
                .methodsNamed("i")
                .forEach { it.addInstructions(0, "return-void") }
        }
    }
