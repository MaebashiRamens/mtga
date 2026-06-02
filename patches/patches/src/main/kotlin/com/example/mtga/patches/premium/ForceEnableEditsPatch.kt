package com.example.mtga.patches.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// Pairs with the always-on premiumGeofenceBypassPatch: L6.U.{e,...} AND
// the user's smsCountry, so geofence stays gating until d() also returns
// true.

private const val FEATURES_DESCRIPTOR = "Lcom/truthsocial/app/data/models/Features;"
private const val FEATURES_CTOR_SIG =
    "ZZLjava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Boolean;Ljava/lang/Boolean;"

@Suppress("unused")
val forceEnableEditsPatch =
    bytecodePatch(
        name = "Force enable Truth+ post editing",
        description = "Forces editsEnabled + editsVisible to true on Features and L6.U gates. Server may still reject.",
        use = false,
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets

            val featuresClass = mutableClassByType(FEATURES_DESCRIPTOR)
            val canonicalCtor =
                featuresClass.methods.firstOrNull {
                    it.name == "<init>" &&
                        it.parameters.joinToString("") { p -> p.type } == FEATURES_CTOR_SIG
                }
                    ?: throw PatchException("$FEATURES_DESCRIPTOR canonical 8-arg ctor not found")
            // .locals 0: clobber parameter registers directly — each is
            // copied into a field with no prior transformation.
            canonicalCtor.addInstructions(
                0,
                """
                sget-object p3, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                sget-object p4, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                """,
            )

            val helper = mutableClassByType(targets.premiumGateHelper.descriptor)
            val shortCircuit = "const/4 v0, 0x1\nreturn v0"
            for (name in listOf("a", "e")) {
                helper.methodsNamed(name).forEach { method ->
                    if (method.returnType != "Z") return@forEach
                    method.addInstructions(0, shortCircuit)
                }
            }
        }
    }
