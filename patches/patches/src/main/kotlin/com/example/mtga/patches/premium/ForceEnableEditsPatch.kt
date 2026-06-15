package com.example.mtga.patches.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.featuresCanonicalCtor
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// Pairs with the always-on premiumGeofenceBypassPatch: the gate helpers AND
// the user's smsCountry, so geofence keeps gating until the geofence helper
// also returns true.

@Suppress("unused")
val forceEnableEditsPatch =
    bytecodePatch(
        name = "Force enable Truth+ post editing",
        description = "Forces editsEnabled + editsVisible to true on Features and the premium gate helpers. Server may still reject.",
        use = false,
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets

            // editsEnabled / editsVisible are the boxed-Boolean ctor args at
            // p3 / p4; these positions are stable across builds (new flags are
            // appended past them). .locals 0 → clobber the param registers.
            featuresCanonicalCtor().addInstructions(
                0,
                """
                sget-object p3, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                sget-object p4, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                """,
            )

            val helper = mutableClassByType(targets.premiumGateHelper.descriptor)
            val shortCircuit = "const/4 v0, 0x1\nreturn v0"
            // Gate-helper letters drift per build; read them from the TargetSet.
            for (name in listOf(targets.premiumGateMethods.editsEnabled, targets.premiumGateMethods.editsVisible)) {
                helper.methodsNamed(name).forEach { method ->
                    if (method.returnType != "Z") return@forEach
                    method.addInstructions(0, shortCircuit)
                }
            }
        }
    }
