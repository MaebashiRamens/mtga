package com.example.mtga.patches.misc

import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import org.w3c.dom.Element

private const val ORIGINAL_PACKAGE = "com.truthsocial.android.app"
private const val RENAMED_PACKAGE = "com.truthsocial.android.app.mtga"
private const val RENAMED_LABEL = "Truth Social (MTGA)"

// AccountManager.addAccount throws SecurityException when the caller's UID
// doesn't own the account type. Rewrite the hard-coded account-type
// literals in DEX to match the renamed package.
@Suppress("unused")
val renameAccountTypesBytecodePatch =
    bytecodePatch(use = false) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val rewrites =
                mapOf(
                    "$ORIGINAL_PACKAGE.account" to "$RENAMED_PACKAGE.account",
                    "$ORIGINAL_PACKAGE.account.tv" to "$RENAMED_PACKAGE.account.tv",
                )

            for (classDef in classDefs.toList()) {
                val mutable = classDefs.getOrReplaceMutable(classDef)
                for (method in mutable.methods) {
                    val impl = method.implementation ?: continue
                    val toReplace = mutableListOf<Pair<Int, String>>()
                    impl.instructions.forEachIndexed { idx, instr ->
                        if (instr.opcode != Opcode.CONST_STRING && instr.opcode != Opcode.CONST_STRING_JUMBO) return@forEachIndexed
                        val ref = (instr as? ReferenceInstruction)?.reference as? StringReference
                            ?: return@forEachIndexed
                        val replacement = rewrites[ref.string] ?: return@forEachIndexed
                        val reg = (instr as OneRegisterInstruction).registerA
                        toReplace.add(idx to "const-string v$reg, \"$replacement\"")
                    }
                    toReplace.forEach { (idx, smali) -> method.replaceInstruction(idx, smali) }
                }
            }
        }
    }

@Suppress("unused")
val renamePackagePatch =
    resourcePatch(
        name = "Rename package to MTGA variant",
        description = "Repackages to $RENAMED_PACKAGE so the patched APK coexists with the Play Store install.",
        use = false,
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))
        dependsOn(renameAccountTypesBytecodePatch)

        execute {
            document("AndroidManifest.xml").use { document ->
                val manifest = document.documentElement
                manifest.setAttribute("package", RENAMED_PACKAGE)

                for (tag in listOf("provider", "permission", "uses-permission")) {
                    val nodes = document.getElementsByTagName(tag)
                    for (i in 0 until nodes.length) {
                        val element = nodes.item(i) as Element
                        for (attr in listOf("android:authorities", "android:name")) {
                            val value = element.getAttribute(attr)
                            if (value.startsWith(ORIGINAL_PACKAGE)) {
                                element.setAttribute(attr, value.replaceFirst(ORIGINAL_PACKAGE, RENAMED_PACKAGE))
                            }
                        }
                    }
                }

                val app = document.getElementsByTagName("application").item(0) as? Element
                app?.setAttribute("android:label", RENAMED_LABEL)
            }

            // authenticator_type drives AccountAuthenticator at runtime;
            // must match the bytecode rewrite above.
            document("res/values/strings.xml").use { document ->
                val strings = document.getElementsByTagName("string")
                for (i in 0 until strings.length) {
                    val element = strings.item(i) as Element
                    if (element.getAttribute("name") == "authenticator_type") {
                        element.textContent = "$RENAMED_PACKAGE.account"
                    }
                }
            }
        }
    }
