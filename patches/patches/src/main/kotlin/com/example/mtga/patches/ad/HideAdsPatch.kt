package com.example.mtga.patches.ad

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

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

            adQueue.methodsNamed("b").forEach { method ->
                method.addInstructions(
                    0,
                    """
                    const/4 v0, 0x0
                    return-object v0
                    """,
                )
            }

            // insertAdsIntoFeed: p3 is the feedItemList; returning it unchanged skips the merge.
            adQueue.methodsNamed("c").forEach { method ->
                method.addInstructions(0, "return-object p3")
            }
        }
    }
