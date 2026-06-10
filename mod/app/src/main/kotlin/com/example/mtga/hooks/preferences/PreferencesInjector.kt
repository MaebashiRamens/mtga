package com.example.mtga.hooks.preferences

import com.example.mtga.common.TargetResolver

/**
 * Strategy for adding an "MTGA Settings" row to Truth Social's built-in
 * Preferences screen. Selected per-build by
 * [com.example.mtga.common.TargetSet.preferencesInjector]; routed from
 * [com.example.mtga.hooks.TruthSocialPreferencesHook].
 *
 * Implementations must be:
 *  - Idempotent at install time (called once per app launch).
 *  - Failure-tolerant: never throw out of [install]; log instead so the
 *    rest of MTGA stays alive.
 */
interface PreferencesInjector {
    /** Injector name for logging. */
    val name: String

    /** Install whatever hooks this build's prefs screen needs. */
    fun install(
        resolver: TargetResolver,
        classLoader: ClassLoader,
    )
}
