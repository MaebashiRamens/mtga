package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.common.ClassTarget
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

@Suppress("unused")
val hideTruthGemsPatch =
    bytecodePatch(
        name = "Hide Truth Gems",
        description = "Removes the gem badge and the Truth Gems banner / drawer button.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            // The gem/badge method letters drift per build, so read them from
            // the per-APK TargetSet lists instead of hardcoding — mirrors the
            // UICleanupHook gem-hiding path.
            val table: List<Pair<ClassTarget, String>> =
                targets.navDrawerAvatarBadgeMethods.map { targets.navDrawerAvatar to it } +
                    targets.accountDrawerGemMethods.map { targets.accountDrawerScreen to it }
            for ((classTarget, methodName) in table) {
                mutableClassByType(classTarget.descriptor)
                    .methodsNamed(methodName)
                    .forEach { it.addInstructions(0, "return-void") }
            }
        }
    }
