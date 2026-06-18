package com.example.mtga.patches.ui

import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.dropFeedItemTypes
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByTypeOrNull
import com.example.mtga.patches.neutraliseComposables

/**
 * Build-time mirror of `HideTopBannerAdPatch` from the LSPosed module.
 *
 * Suppresses the "Proudly sponsored by Truth Social" UFC-style card at the
 * top of the home feed by neutralising three independent renderers:
 *
 *  - [com.example.mtga.common.TargetSet.embeddedAnnouncement] (detail-screen
 *    EmbeddedAnnouncementCard — `ud.d` on v1.27.0, `yd.d` on v1.27.1).
 *  - [com.example.mtga.common.TargetSet.nonNativeAdRenderer] (`AdView.kt`
 *    used by `FeedItemType.NonNativeAd` in the home feed dispatcher —
 *    `k6.b` on v1.27.0, `l6.a` on v1.27.1).
 *  - [com.example.mtga.common.TargetSet.homeAnnouncementRenderer]
 *    (`Announcement.kt` — `La.a` on v1.27.0, `Na.a` on v1.27.1). This is
 *    the actual home-feed banner renderer; the EmbeddedAnnouncementCard
 *    path is detail-screen only.
 *
 * Older Truth Social builds (v1.24.x / v1.26.x) predate these renderers;
 * their TargetSets leave the three fields null and this patch silently
 * skips them.
 */
@Suppress("unused")
val hideTopBannerAdPatch =
    bytecodePatch(
        name = "Hide top banner ad",
        description = "Removes the sponsored \"Proudly sponsored by Truth Social\" card at the top of the home feed.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            if (targets.feedItemMapper != null) {
                // v1.26.2+: the ad/announcement renderers are in-timeline
                // FeedItems. Neutralising their Composables desyncs the slot
                // table and freezes Like/ReTruth (matches the LSPosed bug), so
                // instead drop those FeedItems from the mapper's list — the
                // dispatcher never renders them. No Composable is skipped.
                dropFeedItemTypes("NonNativeAd", "NativeAd", "Announcement")
            } else {
                // ≤v1.26.1: no FeedItem dispatcher. The announcement is a
                // standalone top-banner header (single call site, not
                // interleaved with statuses), so neutralising its Composable is
                // safe here.
                listOfNotNull(
                    targets.embeddedAnnouncement,
                    targets.nonNativeAdRenderer,
                    targets.homeAnnouncementRenderer,
                ).forEach { classTarget ->
                    mutableClassByTypeOrNull(classTarget.descriptor)?.neutraliseComposables()
                }
            }
        }
    }
