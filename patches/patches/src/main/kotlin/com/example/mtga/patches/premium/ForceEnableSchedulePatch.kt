package com.example.mtga.patches.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.featuresCanonicalCtor
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

@Suppress("unused")
val forceEnableSchedulePatch =
    bytecodePatch(
        name = "Force enable Truth+ post scheduling",
        description = "Forces scheduleEnabled + scheduleVisible to true on Features and the premium gate helpers. Server may still reject.",
        use = false,
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets

            // scheduleEnabled / scheduleVisible are the boxed-Boolean ctor args
            // at p5 / p6; stable positions across builds (new flags appended).
            featuresCanonicalCtor().addInstructions(
                0,
                """
                sget-object p5, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                sget-object p6, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                """,
            )

            val helper = mutableClassByType(targets.premiumGateHelper.descriptor)
            val shortCircuit = "const/4 v0, 0x1\nreturn v0"
            // Gate-helper letters drift per build; read them from the TargetSet.
            for (name in listOf(targets.premiumGateMethods.scheduleEnabled, targets.premiumGateMethods.scheduleVisible)) {
                helper.methodsNamed(name).forEach { method ->
                    if (method.returnType != "Z") return@forEach
                    method.addInstructions(0, shortCircuit)
                }
            }
        }
    }
