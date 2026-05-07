package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.common.ClassTarget
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// Truth Gems UI surfaces. All four are @Composable lambdas; replacing the
// body with `return-void` renders nothing.
//
//   navDrawerAvatar.k() — default-zero gem badge
//   navDrawerAvatar.m() — actual-count gem badge
//   accountDrawerScreen.M() — drawer-header gem button
//   accountDrawerScreen.b0() — Truth Gems banner card
//
// All TargetSet-driven so a future R8 rename is one TargetSet entry away.

@Suppress("unused")
val hideTruthGemsPatch =
    bytecodePatch(
        name = "Hide Truth Gems",
        description = "Removes the gem badge and the Truth Gems banner / drawer button.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            val table: List<Pair<ClassTarget, String>> =
                listOf(
                    targets.navDrawerAvatar to "k",
                    targets.navDrawerAvatar to "m",
                    targets.accountDrawerScreen to "M",
                    targets.accountDrawerScreen to "b0",
                )
            for ((classTarget, methodName) in table) {
                mutableClassByType(classTarget.descriptor)
                    .methodsNamed(methodName)
                    .forEach { it.addInstructions(0, "return-void") }
            }
        }
    }
