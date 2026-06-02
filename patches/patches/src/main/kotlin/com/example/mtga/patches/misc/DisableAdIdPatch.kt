package com.example.mtga.patches.misc

import app.revanced.patcher.patch.resourcePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import org.w3c.dom.Element

private val PERMISSIONS_TO_REMOVE =
    setOf(
        "com.google.android.gms.permission.AD_ID",
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
        "android.permission.ACCESS_ADSERVICES_AD_ID",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
    )

@Suppress("unused")
val disableAdIdPatch =
    resourcePatch(
        name = "Disable advertising ID",
        description = "Removes advertising-ID and ad-services permissions from the manifest.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            document("AndroidManifest.xml").use { document ->
                val nodes = document.getElementsByTagName("uses-permission")
                val toRemove = mutableListOf<Element>()
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    val name = element.getAttribute("android:name")
                    if (name in PERMISSIONS_TO_REMOVE) toRemove.add(element)
                }
                toRemove.forEach { it.parentNode.removeChild(it) }
            }
        }
    }
