package com.example.mtga.patches.ui

import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// The Feed class descriptor moved packages (app.* -> core.* in v1.26.2+) and
// the id accessor differs by build: ≤1.26.1 exposes a `feedIdMethod` getter
// (the `id` field is private), 1.26.2+ dropped the getter and made the field
// public — so both the descriptor and the read strategy come from the
// resolved TargetSet and are threaded in here.
private fun feedIdExtract(
    feedDescriptor: String,
    feedIdMethod: String?,
    feedIdField: String,
    item: String,
    id: String,
): String =
    if (feedIdMethod != null) {
        """
        invoke-virtual {$item}, $feedDescriptor->$feedIdMethod()Ljava/lang/String;
        move-result-object $id
        """.trim()
    } else {
        "iget-object $id, $item, $feedDescriptor->$feedIdField:Ljava/lang/String;"
    }

// inputReg may share a register with outputReg / arrayList; the input is
// moved into `save` first.
private fun filterSmali(
    feedDescriptor: String,
    feedIdMethod: String?,
    feedIdField: String,
    inputReg: String,
    outputReg: String,
    save: String,
    arrayList: String,
    iter: String,
    item: String,
    id: String,
    scratch: String,
): String =
    """
    move-object $save, $inputReg
    new-instance $arrayList, Ljava/util/ArrayList;
    invoke-direct {$arrayList}, Ljava/util/ArrayList;-><init>()V
    invoke-interface {$save}, Ljava/util/List;->iterator()Ljava/util/Iterator;
    move-result-object $iter
    :mtga_loop
    invoke-interface {$iter}, Ljava/util/Iterator;->hasNext()Z
    move-result $scratch
    if-eqz $scratch, :mtga_loop_end
    invoke-interface {$iter}, Ljava/util/Iterator;->next()Ljava/lang/Object;
    move-result-object $item
    check-cast $item, $feedDescriptor
    ${feedIdExtract(feedDescriptor, feedIdMethod, feedIdField, item, id)}
    const-string $scratch, "for_you"
    invoke-virtual {$id, $scratch}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result $scratch
    if-nez $scratch, :mtga_loop
    const-string $scratch, "recommended"
    invoke-virtual {$id, $scratch}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result $scratch
    if-nez $scratch, :mtga_loop
    invoke-virtual {$arrayList, $item}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    goto :mtga_loop
    :mtga_loop_end
    move-object $outputReg, $arrayList
    nop
    """

@Suppress("unused")
val hideForYouPatch =
    bytecodePatch(
        name = "Hide For You feed",
        description = "Drops feeds whose id is \"for_you\" or \"recommended\" from the FeedsRepositoryImpl reader.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            val repoClass = mutableClassByType(targets.feedsRepository.descriptor)

            // p()'s early return-object yields a const-zero null; calling
            // iterator() there fails verification. Patch only the last
            // return-object.
            repoClass.methodsNamed("p").forEach { method ->
                if (method.returnType != "Ljava/util/List;") return@forEach
                val impl = method.implementation ?: return@forEach
                val lastReturnIdx =
                    impl.instructions.toList()
                        .withIndex()
                        .lastOrNull { it.value.opcode == Opcode.RETURN_OBJECT }
                        ?.index ?: return@forEach
                method.addInstructionsWithLabels(
                    lastReturnIdx,
                    filterSmali(
                        feedDescriptor = targets.feedClass.descriptor,
                        feedIdMethod = targets.feedIdMethod,
                        feedIdField = targets.feedIdField,
                        inputReg = "v0",
                        outputReg = "v0",
                        save = "v6",
                        arrayList = "v0",
                        iter = "v1",
                        item = "v2",
                        id = "v3",
                        scratch = "v4",
                    ),
                )
            }

            repoClass.methodsNamed("u").forEach { method ->
                if (method.parameterTypes.firstOrNull() != "Ljava/util/List;") return@forEach
                method.addInstructionsWithLabels(
                    0,
                    filterSmali(
                        feedDescriptor = targets.feedClass.descriptor,
                        feedIdMethod = targets.feedIdMethod,
                        feedIdField = targets.feedIdField,
                        inputReg = "p1",
                        outputReg = "p1",
                        save = "v0",
                        arrayList = "v1",
                        iter = "v2",
                        item = "v3",
                        id = "v4",
                        scratch = "v5",
                    ),
                )
            }
        }
    }
