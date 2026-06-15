package com.example.mtga.patches.premium

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.featuresCanonicalCtor

@Suppress("unused")
val enableTvPatch =
    bytecodePatch(
        name = "Enable Truth TV",
        description = "Forces Features.tvEnabled to true on construction. Truth TV becomes visible across the app.",
        use = false,
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            // tvEnabled is the first primitive ctor arg (p1) on every build.
            featuresCanonicalCtor().addInstructions(0, "const/4 p1, 0x1")
        }
    }
