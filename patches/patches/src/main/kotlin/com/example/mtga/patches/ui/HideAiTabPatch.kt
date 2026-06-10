package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.removeInstructions
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// Relies on R8's stable `const/4 size; new-array; sget+const+aput per slot`
// emission pattern for the static tab list, so no extension dex is needed.

@Suppress("unused")
val hideAiTabPatch =
    bytecodePatch(
        name = "Hide AI tab",
        description = "Removes the Truth AI tab from the bottom navigation bar.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            // v1.26.2+ removed the AI tab entirely; the patch is a no-op there.
            val aiDescriptor = targets.bottomNavAiTab?.descriptor ?: return@execute
            val tabsClass = mutableClassByType(targets.bottomNavTabs.descriptor)
            val clinit =
                tabsClass.methodsNamed("<clinit>").firstOrNull()
                    ?: throw PatchException("${targets.bottomNavTabs.name}.<clinit> not found")

            val impl =
                clinit.implementation
                    ?: throw PatchException("${targets.bottomNavTabs.name}.<clinit> has no implementation")

            val instructions = impl.instructions.toList()

            val aiSgetIdx =
                instructions.indexOfFirst { instr ->
                    instr.opcode == Opcode.SGET_OBJECT &&
                        instr is ReferenceInstruction &&
                        (instr.reference as? FieldReference)?.let {
                            it.definingClass == aiDescriptor && it.name == "a"
                        } == true
                }
            if (aiSgetIdx < 0) return@execute

            val aiArrayIndex =
                (instructions.getOrNull(aiSgetIdx + 1) as? NarrowLiteralInstruction)?.narrowLiteral
                    ?: throw PatchException("expected const/4 (array index) after AI sget-object")

            val newArrayIdx =
                (aiSgetIdx - 1 downTo 0).firstOrNull { instructions[it].opcode == Opcode.NEW_ARRAY }
                    ?: throw PatchException("new-array not found before AI sget-object")
            if (newArrayIdx == 0) throw PatchException("no size literal before new-array")

            val sizeInstr =
                instructions[newArrayIdx - 1] as? NarrowLiteralInstruction
                    ?: throw PatchException("expected size literal before new-array")
            val sizeReg = (sizeInstr as OneRegisterInstruction).registerA
            val newSize = sizeInstr.narrowLiteral - 1

            // Collect against pre-removal indices, then replace before
            // removing so the recorded positions stay valid.
            val indexDecrements = mutableListOf<Pair<Int, String>>()
            for (i in (aiSgetIdx + 3) until instructions.size) {
                val instr = instructions[i]
                if (instr.opcode != Opcode.CONST_4) continue
                val lit = (instr as NarrowLiteralInstruction).narrowLiteral
                if (lit > aiArrayIndex && lit < 8) {
                    val reg = (instr as OneRegisterInstruction).registerA
                    indexDecrements.add(i to "const/4 v$reg, 0x${(lit - 1).toString(16)}")
                }
            }

            clinit.replaceInstruction(newArrayIdx - 1, "const/4 v$sizeReg, 0x${newSize.toString(16)}")
            for ((i, smali) in indexDecrements) {
                clinit.replaceInstruction(i, smali)
            }
            clinit.removeInstructions(aiSgetIdx, 3)
        }
    }
