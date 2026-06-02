package com.example.mtga.patches.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.mutableClassByType

private const val FEATURES_DESCRIPTOR = "Lcom/truthsocial/app/data/models/Features;"
private const val FEATURES_CTOR_SIG =
    "ZZLjava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Boolean;"

@Suppress("unused")
val enableTvPatch =
    bytecodePatch(
        name = "Enable Truth TV",
        description = "Forces Features.tvEnabled to true on construction. Truth TV becomes visible across the app.",
        use = false,
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val featuresClass = mutableClassByType(FEATURES_DESCRIPTOR)
            val canonicalCtor =
                featuresClass.methods.firstOrNull {
                    it.name == "<init>" &&
                        it.parameters.joinToString("") { p -> p.type } == FEATURES_CTOR_SIG
                }
                    ?: throw PatchException("$FEATURES_DESCRIPTOR canonical 8-arg ctor not found")
            canonicalCtor.addInstructions(0, "const/4 p1, 0x1")
        }
    }
