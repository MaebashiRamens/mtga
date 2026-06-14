package com.example.mtga.patches

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
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
