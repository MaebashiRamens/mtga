package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

@Suppress("unused")
val hideTruthPlusButtonPatch =
    bytecodePatch(
        name = "Hide TRUTH+ button",
        description = "Removes the TRUTH+ upsell button from the top app bar.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets

            // i() is a Composable returning kotlin.Unit; `return-void`
            // fails verification, so emit `return-object UNIT_SINGLETON`.
            val unitDesc = targets.kotlinUnit.descriptor
            val unitClass = mutableClassByType(unitDesc)
            val unitInstanceField =
                unitClass.staticFields.firstOrNull { it.type == unitDesc }
                    ?: throw PatchException("$unitDesc has no singleton field")

            mutableClassByType(targets.topAppBarFactory.descriptor)
                .methodsNamed(targets.topAppBarTruthPlusMethod)
                .forEach { method ->
                    val returnSmali =
                        when (method.returnType) {
                            "V" -> "return-void"
                            unitDesc ->
                                """
                                sget-object v0, $unitDesc->${unitInstanceField.name}:$unitDesc
                                return-object v0
                                """
                            else -> throw PatchException(
                                "${targets.topAppBarFactory.name}.${targets.topAppBarTruthPlusMethod}: " +
                                    "unexpected return type ${method.returnType}",
                            )
                        }
                    method.addInstructions(0, returnSmali)
                }
        }
    }
