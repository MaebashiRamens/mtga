package com.example.mtga.patches

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.example.mtga.common.TargetSet
import com.example.mtga.common.Targets

/**
 * Truth Social package + calibrated versionNames pulled from the shared
 * `:common` registry. Adding a new build to `Targets.knownVersions` extends
 * every patch's `compatibleWith()` list automatically.
 */
internal const val MTGA_TARGET_PACKAGE = Targets.PACKAGE
internal val MTGA_COMPATIBLE_VERSIONS: Array<String> get() = Targets.knownVersionNames

/**
 * Read the target APK's `BuildConfig.VERSION_NAME` directly from the
 * DEX class table — kotlin compiles it as a static-final String with a
 * `StringEncodedValue` initial value, so we can pull the literal at
 * patch-apply time without binary-manifest parsing.
 */
@Suppress("DEPRECATION")
internal fun BytecodePatchContext.readBuildConfigVersionName(): String? {
    val buildConfig = classDefs.firstOrNull { it.type == "Lcom/truthsocial/app/ts/BuildConfig;" } ?: return null
    val field = buildConfig.staticFields.firstOrNull { it.name == "VERSION_NAME" } ?: return null
    val encoded = field.initialValue as? StringEncodedValue ?: return null
    return encoded.value
}

/**
 * Per-APK [TargetSet] resolution. Reading this as a property inside
 * `execute { }` gives every patch the right per-APK obfuscated names —
 * one `.rvp` covers every calibrated version. Falls back to
 * [Targets.latest] when the version can't be determined or isn't yet
 * calibrated.
 */
internal val BytecodePatchContext.mtgaTargets: TargetSet
    get() {
        val versionName = readBuildConfigVersionName() ?: return Targets.latest
        return Targets.forVersionName(versionName) ?: Targets.latest
    }

/**
 * Look up a class by DEX type descriptor (e.g. `"Lac/c;"`). Returns a
 * [MutableClassDef]; throws [PatchException] if missing (patches are
 * calibrated against a specific build, so a missing class is a hard error).
 */
@Suppress("DEPRECATION")
internal fun BytecodePatchContext.mutableClassByType(type: String): MutableClassDef {
    val classDef: ClassDef =
        classDefs.firstOrNull { it.type == type }
            ?: throw PatchException("$type not found in target APK")
    return classDefs.getOrReplaceMutable(classDef)
}

/**
 * Find the [MutableClassDef] by type, or null if missing. Use when a class
 * may legitimately be absent (e.g. SearchAIUseCase when Truth Social drops it).
 */
@Suppress("DEPRECATION")
internal fun BytecodePatchContext.mutableClassByTypeOrNull(type: String): MutableClassDef? {
    val classDef: ClassDef = classDefs.firstOrNull { it.type == type } ?: return null
    return classDefs.getOrReplaceMutable(classDef)
}

/** Return all methods on a class with the given name. */
internal fun MutableClassDef.methodsNamed(name: String): List<MutableMethod> = methods.filter { it.name == name }

/**
 * Build-time equivalent of [com.example.mtga.hooks.UICleanupHook.noopAllComposables].
 * Static + void-returning + has-Composer-arg methods get their body
 * replaced with `return-void`.
 */
internal fun MutableClassDef.neutraliseComposables(): Int {
    var count = 0
    methods.forEach { method ->
        val isStatic = method.accessFlags and AccessFlags.STATIC.value != 0
        if (!isStatic) return@forEach
        if (method.returnType != "V") return@forEach
        val hasComposer =
            method.parameters.any { p ->
                p.type.startsWith("Landroidx/compose/runtime/") ||
                    COMPOSER_TYPE_PREFIX_REGEX.matches(p.type)
            }
        if (!hasComposer) return@forEach
        method.addInstructions(0, "return-void")
        count++
    }
    return count
}

// Mirror of [com.example.mtga.hooks.UICleanupHook]'s
// COMPOSER_PACKAGE_REGEX (DEX descriptor form).
private val COMPOSER_TYPE_PREFIX_REGEX = Regex("""^L[a-z]0/.+;$""")

/**
 * The canonical (non-synthetic) `Features` constructor on the per-APK
 * [mtgaTargets] `featuresClass`: shape `(ZZ + N boxed Booleans)V`. R8 appends
 * a new flag to the end each release (6 Booleans on ≤1.26.1, 8 on 1.26.2, 9 on
 * 1.27.x), so match the stable `ZZ`+Boolean prefix rather than a fixed arity.
 * Leading args keep their positions across builds, so the premium patches'
 * positional writes (`p1` tvEnabled, `p3`/`p4` edits, `p5`/`p6` schedule) stay
 * valid. Throws [PatchException] if no such ctor exists.
 */
internal fun BytecodePatchContext.featuresCanonicalCtor(): MutableMethod {
    val cls = mutableClassByType(mtgaTargets.featuresClass.descriptor)
    return cls.methods.firstOrNull {
        it.name == "<init>" && FEATURES_CTOR_SHAPE_REGEX.matches(it.parameters.joinToString("") { p -> p.type })
    } ?: throw PatchException("${mtgaTargets.featuresClass.name}: canonical ZZ+Boolean ctor not found")
}

private val FEATURES_CTOR_SHAPE_REGEX = Regex("""^ZZ(Ljava/lang/Boolean;)+$""")

/** R8 keeps this FQN (Moshi-serialised); stable on every FeedItem-dispatcher build. */
private const val FEED_ITEM_TYPE_DESCRIPTOR = "Lcom/truthsocial/core/data/models/FeedItemType;"

/**
 * Build-time equivalent of `UICleanupHook.installFeedItemFilter`: drop FeedItems
 * whose `FeedItemType.name()` is in [typeNames] from the home-timeline mapper's
 * returned `ArrayList`, so the ad / announcement / live renderer Composables are
 * never invoked.
 *
 * Compose-SAFE — it filters DATA, never empties a Composable body. Emptying a
 * Composable (the old `neutraliseComposables` path) desyncs the Compose slot
 * table and freezes Like/ReTruth recomposition; filtering the list the
 * dispatcher iterates avoids that entirely.
 *
 * No-op when [TargetSet.feedItemMapper] / [TargetSet.feedItemWrapper] aren't
 * calibrated (builds ≤1.26.1 predate the FeedItem dispatcher). On the three
 * calibrated builds (v1.26.2/1.27.0/1.27.1) the mapper is identical in shape:
 * it builds the list in `v0` and ends with a single `return-object v0`, with
 * `.locals 5` so `v1`..`v4` are free temps at the return. Throws [PatchException]
 * if a future build breaks that assumption rather than emitting wrong bytecode.
 */
internal fun BytecodePatchContext.dropFeedItemTypes(vararg typeNames: String) {
    val targets = mtgaTargets
    val mapperDesc = targets.feedItemMapper?.descriptor ?: return
    val wrapperDesc = targets.feedItemWrapper?.descriptor ?: return
    val cls = mutableClassByTypeOrNull(mapperDesc) ?: return
    val method =
        cls.methodsNamed(targets.feedItemMapperMethod)
            .firstOrNull { it.returnType == "Ljava/util/ArrayList;" }
            ?: throw PatchException("$mapperDesc.${targets.feedItemMapperMethod}: FeedItem mapper not found")

    val instructions = method.implementation?.instructions?.toList() ?: return
    val ret =
        instructions.withIndex().lastOrNull { it.value.opcode == Opcode.RETURN_OBJECT }
            ?: throw PatchException("$mapperDesc.${targets.feedItemMapperMethod}: no return-object")
    val retReg = (ret.value as OneRegisterInstruction).registerA
    if (retReg != 0) {
        throw PatchException("$mapperDesc.${targets.feedItemMapperMethod}: unexpected return register v$retReg (expected v0)")
    }

    // Unique label suffix so HideTopBannerAd and HideLiveCarousel can each
    // inject a pass into the same mapper without colliding.
    val tag = typeNames.first().filter(Char::isLetterOrDigit)
    val checks =
        typeNames.joinToString("\n") { name ->
            """
            const-string v4, "$name"
            invoke-virtual {v3, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v4
            if-nez v4, :mtga_rm_$tag
            """.trimIndent()
        }
    method.addInstructionsWithLabels(
        ret.index,
        """
        invoke-virtual {v0}, Ljava/util/ArrayList;->iterator()Ljava/util/Iterator;
        move-result-object v1
        :mtga_loop_$tag
        invoke-interface {v1}, Ljava/util/Iterator;->hasNext()Z
        move-result v4
        if-eqz v4, :mtga_end_$tag
        invoke-interface {v1}, Ljava/util/Iterator;->next()Ljava/lang/Object;
        move-result-object v2
        check-cast v2, $wrapperDesc
        iget-object v3, v2, $wrapperDesc->${targets.feedItemTypeField}:$FEED_ITEM_TYPE_DESCRIPTOR
        invoke-virtual {v3}, Ljava/lang/Enum;->name()Ljava/lang/String;
        move-result-object v3
        $checks
        goto :mtga_loop_$tag
        :mtga_rm_$tag
        invoke-interface {v1}, Ljava/util/Iterator;->remove()V
        goto :mtga_loop_$tag
        :mtga_end_$tag
        nop
        """.trimIndent(),
    )
}

/**
 * Overwrite the first `List` parameter of every [methodName] on [cls] with
 * `Collections.emptyList()` at method entry. Build-time equivalent of the
 * runtime arg-empty: the (Composable) renderer still runs its full group
 * machinery and renders nothing via its own `isEmpty()` branch, so — unlike
 * `neutraliseComposables` — it doesn't desync the slot table. Used for the
 * live carousel renderer, which on newer builds is a list-driven Composable
 * that the home screen mounts as a header outside the FeedItem mapper.
 */
internal fun BytecodePatchContext.emptyFirstListArg(
    cls: MutableClassDef,
    methodName: String,
) {
    cls.methodsNamed(methodName).forEach { method ->
        var reg = 0
        var listReg = -1
        for (p in method.parameters) {
            if (p.type == "Ljava/util/List;") {
                listReg = reg
                break
            }
            reg += if (p.type == "J" || p.type == "D") 2 else 1
        }
        if (listReg < 0) return@forEach
        method.addInstructions(
            0,
            """
            invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object p$listReg
            """.trimIndent(),
        )
    }
}
