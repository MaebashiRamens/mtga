package com.example.mtga.patches

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.example.mtga.common.TargetSet
import com.example.mtga.common.Targets

/**
 * Truth Social package + the calibrated versionNames, both pulled from the
 * shared `:common` registry so adding a new build to `Targets.knownVersions`
 * automatically extends every patch's `compatibleWith()` list.
 */
internal const val MTGA_TARGET_PACKAGE = Targets.PACKAGE
internal val MTGA_COMPATIBLE_VERSIONS: Array<String> get() = Targets.knownVersionNames

/**
 * The [TargetSet] that patches build their smali against. Resolved at
 * patch-COMPILE time, not patch-apply time — `BytecodePatchContext` in
 * patcher v22 doesn't expose `packageVersion`, so per-version dispatch
 * inside `execute { }` isn't directly available. The realistic path when
 * a future Truth Social build diverges from the current calibration:
 *
 *   - All known versions share obfuscated names (current state for
 *     v1.24.6 ↔ v1.24.8): no action needed; the same `.rvp` works for
 *     every entry in `Targets.knownVersions`.
 *
 *   - A future version genuinely renames things: ship a separate `.rvp`
 *     for that major version range (set `MTGA_COMPATIBLE_VERSIONS` to
 *     just the new versions, point this at the new TargetSet).
 *
 *   - The matched-by-content alternative is fingerprints — match
 *     methods by string literals / opcodes / parameter types rather than
 *     by R8-renamed names. Refactor to fingerprints if you want one
 *     `.rvp` to cover divergent obfuscations.
 *
 * Pinning to [Targets.latest] keeps the names in lockstep with the LSPosed
 * module's calibration — adding a new entry to `Targets.knownVersions`
 * automatically rebases the patches when its `version` is bumped.
 */
internal val mtgaTargets: TargetSet get() = Targets.latest

/**
 * Look up a class by its DEX type descriptor (e.g. `"Lac/c;"`) and return a
 * [MutableClassDef]. Throws [PatchException] if the class is missing — patches
 * are calibrated against a specific build, so a missing class is a hard error.
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
 * may legitimately be absent (e.g. SearchAIUseCase if Truth Social drops it).
 */
@Suppress("DEPRECATION")
internal fun BytecodePatchContext.mutableClassByTypeOrNull(type: String): MutableClassDef? {
    val classDef: ClassDef = classDefs.firstOrNull { it.type == type } ?: return null
    return classDefs.getOrReplaceMutable(classDef)
}

/** Return all methods on a class with the given name. */
internal fun MutableClassDef.methodsNamed(name: String): List<MutableMethod> = methods.filter { it.name == name }
