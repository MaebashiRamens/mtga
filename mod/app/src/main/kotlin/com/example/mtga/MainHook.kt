package com.example.mtga

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetSet
import com.example.mtga.common.Targets
import com.example.mtga.config.Settings
import com.example.mtga.config.SettingsHolder
import com.example.mtga.hooks.AdBlockHook
import com.example.mtga.hooks.AnalyticsBlockHook
import com.example.mtga.hooks.BaseHook
import com.example.mtga.hooks.FeatureFlagHook
import com.example.mtga.hooks.InAppSettingsHook
import com.example.mtga.hooks.IntegrityBypassHook
import com.example.mtga.hooks.NotificationBadgeFixHook
import com.example.mtga.hooks.OkHttpAdInterceptorHook
import com.example.mtga.hooks.TruthSocialPreferencesHook
import com.example.mtga.hooks.UICleanupHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * MTGA entrypoint. Defers hook installation until Application.onCreate so we
 * can read PackageInfo.versionCode and pick the matching [TargetSet]. If the
 * running build is not in [Targets.knownVersions], we abort with a Toast
 * warning rather than binding hooks to wrong obfuscated symbols.
 */
class MainHook : IXposedHookLoadPackage {
    companion object {
        const val TAG = "MTGA"
        const val PACKAGE = "com.truthsocial.android.app"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PACKAGE) return

        XposedBridge.log("[$TAG] Loaded into ${lpparam.packageName}")

        // We need a Context to read versionCode. Hook Application.onCreate
        // and install everything from there. Class lookups happen at that
        // point so they see the host's classloader.
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    runCatching { installHooks(app, lpparam.classLoader) }
                        .onFailure { XposedBridge.log("[$TAG] install failed: ${it.message}") }
                }
            },
        )
    }

    private fun installHooks(
        app: Application,
        classLoader: ClassLoader,
    ) {
        SettingsHolder.bind(app)

        val pi = app.packageManager.getPackageInfo(app.packageName, 0)

        @Suppress("DEPRECATION")
        val versionCode = pi.versionCode
        val versionName = pi.versionName ?: "?"

        val targets = Targets.forVersionCode(versionCode)
        if (targets == null) {
            val supported = Targets.knownVersions.joinToString { it.buildId.versionName }
            warnUser(
                app,
                "MTGA: Truth Social $versionName ($versionCode) is not calibrated. " +
                    "Hooks disabled. Tested versions: $supported.",
            )
            return
        }
        XposedBridge.log("[$TAG] Truth Social $versionName ($versionCode) — calibrated")

        val hooks: List<Pair<BaseHook, String?>> =
            listOf(
                TruthSocialPreferencesHook(targets) to null, // always on — primary in-app entry to MTGA settings
                InAppSettingsHook(targets) to null, // always on — fallback triple-tap gateway
                FeatureFlagHook(targets) to null, // checks each toggle internally
                AdBlockHook(targets) to SettingKeys.AdBlock,
                OkHttpAdInterceptorHook(targets) to SettingKeys.AdBlock,
                AnalyticsBlockHook(targets) to SettingKeys.AnalyticsBlock,
                IntegrityBypassHook(targets) to SettingKeys.IntegrityBypass,
                UICleanupHook(targets) to null, // checks per-feature toggles internally
                NotificationBadgeFixHook(targets) to SettingKeys.ClearAlertBadge,
            )

        for ((hook, gate) in hooks) {
            if (gate != null && !Settings.isOn(gate)) {
                XposedBridge.log("[$TAG] ${hook.name} skipped (toggle '$gate' off)")
                continue
            }
            try {
                hook.hook(classLoader)
                XposedBridge.log("[$TAG] ${hook.name} applied")
            } catch (e: Throwable) {
                XposedBridge.log("[$TAG] ${hook.name} failed: ${e.message}")
            }
        }
    }

    private fun warnUser(
        app: Application,
        msg: String,
    ) {
        XposedBridge.log("[$TAG] $msg")
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }
    }
}
