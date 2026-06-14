package com.example.mtga.patches.ui

import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByTypeOrNull
import com.example.mtga.patches.neutraliseComposables

/**
 * Build-time mirror of the `HideLiveCarousel` runtime hook.
 *
 * Suppresses the top-of-home-feed livestream strip introduced in v1.27.0:
 *
 *  - [com.example.mtga.common.TargetSet.liveContentCarousel]
 *    (`LiveContentCarouselKt` — `wd.j` on v1.27.0, `Ad.o` on v1.27.1).
 *  - [com.example.mtga.common.TargetSet.extraLiveRenderers] — peripheral
 *    file classes whose Composables render the same row (chip strip +
 *    LiveTVCard, e.g. `Ua.O` / `mb.q` on v1.27.0, `Wa.O` / `ob.q` on
 *    v1.27.1).
 *
 * Older builds (v1.24.x / v1.26.x) leave both fields null/empty and the
 * patch is a no-op against them.
 */
@Suppress("unused")
val hideLiveCarouselPatch =
    bytecodePatch(
        name = "Hide live content carousel",
        description = "Removes the livestream avatar carousel at the top of the home feed.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            targets.liveContentCarousel?.let { classTarget ->
                mutableClassByTypeOrNull(classTarget.descriptor)?.neutraliseComposables()
            }
            for (classTarget in targets.extraLiveRenderers) {
                mutableClassByTypeOrNull(classTarget.descriptor)?.neutraliseComposables()
            }
        }
    }
