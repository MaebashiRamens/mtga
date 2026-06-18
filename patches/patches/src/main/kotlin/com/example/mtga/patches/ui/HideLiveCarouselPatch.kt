package com.example.mtga.patches.ui

import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.dropFeedItemTypes
import com.example.mtga.patches.emptyFirstListArg
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
            // In-timeline live items (when dispatched as FeedItems): drop them
            // from the mapper list. No-op on builds without the dispatcher.
            dropFeedItemTypes("LiveShowsCarousel", "ChannelGuideItem")

            // The carousel renderer itself is mounted as a home-screen header
            // (outside the mapper) on newer builds. Empty its list arg instead
            // of neutralising it — the Composable runs its group machinery and
            // renders nothing via its own isEmpty() branch, so no slot desync.
            targets.liveContentCarousel?.let { classTarget ->
                mutableClassByTypeOrNull(classTarget.descriptor)?.let { cls ->
                    emptyFirstListArg(cls, targets.liveContentCarouselMethod)
                }
            }

            // Extra renderers (chip strip "See Less Often" header / TV card)
            // are standalone headers, not interleaved with statuses, so
            // neutralising their Composables is safe.
            for (classTarget in targets.extraLiveRenderers) {
                mutableClassByTypeOrNull(classTarget.descriptor)?.neutraliseComposables()
            }
        }
    }
