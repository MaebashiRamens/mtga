package com.example.mtga.patches.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// premiumGateMethods.geofence (L6.U.d on v1.26.x) is the smsCountry=="US"
// geofence. The other helpers AND their feature flag with it, so forcing it
// true is the prerequisite for the ForceEnable* patches to surface buttons.
// The R8 letters drift between builds, so read the per-APK TargetSet rather
// than hardcoding `d` — mirrors FeatureFlagHook.patchPremiumGate.

@Suppress("unused")
val premiumGeofenceBypassPatch =
    bytecodePatch(
        name = "Bypass Truth+ geofence",
        description = "Forces the smsCountry == \"US\" check on premiumGateHelper.d(user) to always return true.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            val geofenceMethod = targets.premiumGateMethods.geofence
            mutableClassByType(targets.premiumGateHelper.descriptor)
                .methods
                .filter { it.name == geofenceMethod && it.returnType == "Z" }
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
