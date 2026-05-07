package com.example.mtga.hooks

import com.example.mtga.common.TargetSet

/**
 * Each hook receives the [TargetSet] for the host build at construction time
 * so it never has to hard-code obfuscated names. If the host's versionCode is
 * not in [com.example.mtga.common.Targets.knownVersions], MainHook does not
 * instantiate any hooks and instead surfaces a startup warning.
 */
abstract class BaseHook(
    protected val targets: TargetSet,
) {
    abstract val name: String

    abstract fun hook(classLoader: ClassLoader)
}
