package com.example.mtga.patches.misc

import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.example.mtga.common.Targets
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE

private const val SUFFIX = "-mtga-patched"

private val KNOWN_VERSION_NAMES: Set<String>
    get() = Targets.knownVersionNames.toSet()

private fun isUnpatchedKnownVersion(literal: String): Boolean =
    literal in KNOWN_VERSION_NAMES && !literal.endsWith(SUFFIX)

// BuildConfig.VERSION_NAME is a compile-time constant so javac inlines the
// literal at every call site. A manifest-only rewrite wouldn't reach the
// AppBuildInfo path that backs the About screen.
@Suppress("unused")
val mtgaPatchedSuffixBytecodePatch =
    bytecodePatch(use = true) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            for (classDef in classDefs.toList()) {
                val mutable = classDefs.getOrReplaceMutable(classDef)
                for (method in mutable.methods) {
                    val impl = method.implementation ?: continue
                    val toReplace = mutableListOf<Pair<Int, String>>()
                    impl.instructions.forEachIndexed { idx, instr ->
                        if (instr.opcode != Opcode.CONST_STRING && instr.opcode != Opcode.CONST_STRING_JUMBO) return@forEachIndexed
                        val ref = (instr as? ReferenceInstruction)?.reference as? StringReference
                            ?: return@forEachIndexed
                        if (!isUnpatchedKnownVersion(ref.string)) return@forEachIndexed
                        val reg = (instr as OneRegisterInstruction).registerA
                        toReplace.add(idx to "const-string v$reg, \"${ref.string}$SUFFIX\"")
                    }
                    toReplace.forEach { (idx, smali) -> method.replaceInstruction(idx, smali) }
                }
            }
        }
    }

@Suppress("unused")
val mtgaPatchedSuffixPatch =
    resourcePatch(
        name = "Tag versionName with $SUFFIX",
        description = "Appends \"$SUFFIX\" to the manifest versionName and inlined BuildConfig.VERSION_NAME literals.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))
        dependsOn(mtgaPatchedSuffixBytecodePatch)

        execute {
            // apktool strips android:versionName during decode; setting
            // the attribute back forces aapt2 link to re-encode it.
            document("AndroidManifest.xml").use { document ->
                val manifest = document.documentElement
                val current = manifest.getAttribute("android:versionName")
                val base =
                    when {
                        current.isEmpty() -> Targets.latest.buildId.versionName
                        current.endsWith(SUFFIX) -> current.removeSuffix(SUFFIX)
                        else -> current
                    }
                manifest.setAttribute("android:versionName", "$base$SUFFIX")
            }
        }
    }
