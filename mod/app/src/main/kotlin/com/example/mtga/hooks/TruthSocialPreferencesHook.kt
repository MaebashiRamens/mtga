package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.PreferencesInjectorKind
import com.example.mtga.common.TargetResolver
import com.example.mtga.hooks.preferences.LegacyPreferencesInjector
import com.example.mtga.hooks.preferences.ModernPreferencesInjector
import com.example.mtga.hooks.preferences.PreferencesInjector
import de.robv.android.xposed.XposedBridge

/**
 * Dispatch Preferences-screen injection to the per-version implementation
 * selected by [com.example.mtga.common.TargetSet.preferencesInjector]:
 *
 *  - [PreferencesInjectorKind.Legacy] → [LegacyPreferencesInjector]
 *    (v1.24.6 / v1.24.8 / v1.26.1: `sa.j.p` + `ic.b`/`ic.d`).
 *  - [PreferencesInjectorKind.Modern] → [ModernPreferencesInjector]
 *    (v1.26.2 / v1.27.0).
 *
 * A small dispatcher keeps adding a new build family to writing a fresh
 * injector and pointing [TargetSet] at it.
 */
class TruthSocialPreferencesHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "TruthSocialPreferences"

    override fun hook(classLoader: ClassLoader) {
        val injector: PreferencesInjector =
            when (targets.preferencesInjector) {
                PreferencesInjectorKind.Legacy -> LegacyPreferencesInjector()
                PreferencesInjectorKind.Modern -> ModernPreferencesInjector()
            }
        XposedBridge.log("[$TAG] $name: dispatching to ${injector.name} (${targets.preferencesInjector})")
        injector.install(resolver, classLoader)
    }
}
