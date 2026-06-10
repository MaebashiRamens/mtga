package com.example.mtga.patches.misc

import app.revanced.patcher.patch.resourcePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import org.w3c.dom.Element

// Play-AAB builds advertise required splits even when nothing in the
// missing splits is needed at runtime, so `pm install` of the base alone
// fails with INSTALL_FAILED_MISSING_SPLIT. Strip the requirement.

@Suppress("unused")
val stripSplitRequirementPatch =
    resourcePatch(
        name = "Strip split requirement",
        description = "Removes isSplitRequired / requiredSplitTypes / vending.splits.required so the patched APK installs standalone.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            document("AndroidManifest.xml").use { document ->
                val manifest = document.documentElement
                manifest.removeAttribute("android:isSplitRequired")
                manifest.removeAttribute("android:requiredSplitTypes")
                manifest.removeAttribute("android:splitTypes")

                val metas = document.getElementsByTagName("meta-data")
                for (i in 0 until metas.length) {
                    val element = metas.item(i) as Element
                    if (element.getAttribute("android:name") == "com.android.vending.splits.required") {
                        element.setAttribute("android:value", "false")
                    }
                }
            }
        }
    }
