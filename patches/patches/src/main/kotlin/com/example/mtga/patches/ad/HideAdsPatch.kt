package com.example.mtga.patches.ad

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// AdQueueManager —
//   `b(zone, continuation)`  fetchAd        → `return null`
//   `c(adIndexes, zone, feedItemList, cont)` insertAdsIntoFeed → `return p3`
// (Class is known as `v7.d` on v1.24.6 / v1.24.8.)

@Suppress("unused")
val hideAdsPatch =
    bytecodePatch(
        name = "Hide ads",
        description = "Removes /truth/ads responses, AdQueueManager fetches and feed insertions.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            val adQueue = mutableClassByType(targets.adQueueManager.descriptor)

            // fetchAd → return null
            adQueue.methodsNamed("b").forEach { method ->
                method.addInstructions(
                    0,
                    """
                    const/4 v0, 0x0
                    return-object v0
                    """,
                )
            }

            // insertAdsIntoFeed → return feedItemList (p3)
            // Non-static: p0=this, p1=adIndexes, p2=zone, p3=feedItemList, p4=Continuation.
            adQueue.methodsNamed("c").forEach { method ->
                method.addInstructions(0, "return-object p3")
            }
        }
    }
