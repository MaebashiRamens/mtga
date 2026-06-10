package com.example.mtga.patches.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// L6.U.d(user) is the smsCountry=="US" geofence. The other helpers
// (a/c/e/g) AND their feature flag with it, so forcing d() true is the
// prerequisite for the ForceEnable* patches to surface buttons.

@Suppress("unused")
val premiumGeofenceBypassPatch =
    bytecodePatch(
        name = "Bypass Truth+ geofence",
        description = "Forces the smsCountry == \"US\" check on premiumGateHelper.d(user) to always return true.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            mutableClassByType(targets.premiumGateHelper.descriptor)
                .methods
                .filter { it.name == "d" && it.returnType == "Z" }
                .forEach {
                    it.addInstructions(
                        0,
                        """
                        const/4 v0, 0x1
                        return v0
                        """,
                    )
                }
        }
    }
