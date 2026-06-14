package com.example.mtga.hooks

import android.content.pm.PackageInfo
import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetResolver
import com.example.mtga.common.Targets
import com.example.mtga.config.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Append a `-mtga-patched` suffix to Truth Social's reported version at
 * runtime. The build-time [com.example.mtga.patches.misc.MtgaPatchedSuffixPatch]
 * rewrites the inlined version literal directly inside the DEX, but users
 * who run MTGA via LSPosed on a stock Truth Social APK never go through
 * that patcher — this hook covers the LSPosed path.
 *
 * R8 inlines property getters on Truth Social's `AppBuildInfo` data class
 * into const-string literals at every call site, so there is no single
 * "versionName getter" to hook. We instead patch the readers we *can*
 * reach without static analysis:
 *
 *  1. `BuildConfig.VERSION_NAME` — the static field. Effective for callers
 *     that read it dynamically (rare after R8 inlining, but free to hook).
 *  2. `PackageManager.getPackageInfo` — system-API path for any code that
 *     queries its own package metadata at runtime.
 *  3. `AppBuildInfo.toString()` — used by analytics / crashlytics payloads.
 *     Rewrites the embedded `versionName=X` substring.
 *
 * The user-visible About screen relies on R8-inlined literals so it stays
 * unmodified by this hook. For an exact parity with the revanced patch
 * the user should use the .rvp APK flow; this hook is best-effort for the
 * LSPosed-only deployment.
 */
class VersionSuffixHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "VersionSuffix"

    override fun hook(classLoader: ClassLoader) {
        if (!Settings.isOn(SettingKeys.AppendMtgaSuffix)) return
        hookPackageManagerGetPackageInfo(classLoader)
        rewriteBuildConfigField(classLoader)
        hookAppBuildInfoToString(classLoader)
        hookVersionStringResource(classLoader)
    }

    private fun hookPackageManagerGetPackageInfo(classLoader: ClassLoader) {
        val cls =
            runCatching {
                XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            }.getOrElse {
                XposedBridge.log("[$TAG] ApplicationPackageManager not found: ${it.message}")
                return
            }
        XposedBridge.hookAllMethods(
            cls,
            "getPackageInfo",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val info = param.result as? PackageInfo ?: return
                    if (info.packageName != Targets.PACKAGE) return
                    val current = info.versionName ?: return
                    if (current.endsWith(SUFFIX)) return
                    info.versionName = "$current$SUFFIX"
                }
            },
        )
        XposedBridge.log("[$TAG] PackageManager.getPackageInfo hooked for version suffix")
    }

    private fun rewriteBuildConfigField(classLoader: ClassLoader) {
        val cls =
            runCatching { classLoader.loadClass("com.truthsocial.app.ts.BuildConfig") }
                .getOrElse {
                    XposedBridge.log("[$TAG] BuildConfig class missing: ${it.message}")
                    return
                }
        val current =
            runCatching {
                XposedHelpers.getStaticObjectField(cls, "VERSION_NAME") as? String
            }.getOrNull() ?: return
        if (current.endsWith(SUFFIX)) return
        runCatching {
            XposedHelpers.setStaticObjectField(cls, "VERSION_NAME", "$current$SUFFIX")
            XposedBridge.log("[$TAG] BuildConfig.VERSION_NAME = $current$SUFFIX")
        }
    }

    private fun hookAppBuildInfoToString(classLoader: ClassLoader) {
        val target = targets.appBuildInfo ?: return
        val cls =
            runCatching { XposedHelpers.findClass(target.name, classLoader) }
                .getOrElse {
                    XposedBridge.log("[$TAG] AppBuildInfo missing (${target.name}): ${it.message}")
                    return
                }
        XposedBridge.hookAllMethods(
            cls,
            "toString",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = param.result as? String ?: return
                    val rewritten =
                        VERSION_FIELD_REGEX.replace(original) { match ->
                            val bare = match.groupValues[1]
                            if (bare.endsWith(SUFFIX)) {
                                match.value
                            } else {
                                "versionName=$bare$SUFFIX${match.groupValues[2]}"
                            }
                        }
                    if (rewritten != original) param.result = rewritten
                }
            },
        )
        XposedBridge.log("[$TAG] AppBuildInfo.toString hooked (${target.name})")
    }

    /**
     * Hook `Resources.getString` for [TargetSet.resStringVersion]
     * (`R.string.version`, the `"Version: %1$s"` template used by the
     * Preferences screen footer). When the formatted result still ends
     * with a bare version literal we append the suffix.
     *
     * This is the only path the Preferences footer line goes through:
     * the Composable calls `stringResource(R.string.version, versionName)`,
     * which dispatches to `Resources.getString(int, vararg Object)`. R8
     * inlining doesn't touch this since the formatting happens inside
     * Android framework code at runtime.
     */
    private fun hookVersionStringResource(classLoader: ClassLoader) {
        val resId = targets.resStringVersion
        if (resId == 0) return
        val resourcesCls = classLoader.loadClass("android.content.res.Resources")
        XposedBridge.hookAllMethods(
            resourcesCls,
            "getString",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = param.args.firstOrNull() as? Int ?: return
                    if (id != resId) return
                    val original = param.result as? String ?: return
                    val rewritten = appendSuffixToBareVersion(original) ?: return
                    param.result = rewritten
                }
            },
        )
        XposedBridge.log("[$TAG] Resources.getString hooked for R.string.version (0x${resId.toString(16)})")
    }

    /**
     * Append [SUFFIX] to the version literal inside [text] iff the text
     * contains a `MAJOR.MINOR.PATCH` triple not already followed by the
     * suffix. Returns `null` if no rewrite is needed (caller leaves the
     * result untouched).
     */
    private fun appendSuffixToBareVersion(text: String): String? {
        val match = BARE_VERSION_REGEX.find(text) ?: return null
        val before = text.substring(0, match.range.first)
        val version = match.value
        val after = text.substring(match.range.last + 1)
        if (after.startsWith(SUFFIX)) return null
        return "$before$version$SUFFIX$after"
    }

    companion object {
        const val SUFFIX = "-mtga-patched"

        // Matches `versionName=X.Y.Z,` or `versionName=X.Y.Z)` inside a
        // Kotlin-generated toString. Digits + dots only; the trailing
        // delimiter capture rebuilds the exact terminator.
        private val VERSION_FIELD_REGEX = Regex("""versionName=([0-9.]+)([,)])""")

        // MAJOR.MINOR.PATCH literal — strict enough to avoid matching the
        // version-code parenthetical or unrelated dotted numbers in the
        // surrounding text. Matches the FIRST such triple in the string.
        private val BARE_VERSION_REGEX = Regex("""\b\d+\.\d+\.\d+\b""")
    }
}
