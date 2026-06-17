package com.example.mtga.hooks

import com.example.mtga.common.TargetResolver
import com.example.mtga.common.TargetSet

/**
 * Each hook receives a [TargetResolver] at construction so it never
 * hard-codes obfuscated names. For builds in
 * [com.example.mtga.common.Targets.knownVersions], MainHook injects a
 * [com.example.mtga.common.StaticResolver] and the hook sees the precomputed
 * [TargetSet] verbatim. For unknown builds, MainHook injects a
 * [com.example.mtga.FallbackResolver] that wraps the latest known set
 * and tries dynamic discovery for symbols with stable runtime anchors.
 *
 * Hooks consuming only R8-obfuscated names can read [targets] directly;
 * those consuming FQN-stable classes, named resources, or route singletons
 * should go through [resolver] so the fallback path can degrade gracefully.
 */
abstract class BaseHook(
    protected val resolver: TargetResolver,
) {
    protected val targets: TargetSet get() = resolver.targets

    abstract val name: String

    abstract fun hook(classLoader: ClassLoader)
}
