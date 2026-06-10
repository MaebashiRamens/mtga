package com.example.mtga.common

/**
 * Decouples hook code from the source of obfuscated symbol coordinates.
 *
 * For builds in [Targets.knownVersions], MainHook injects a [StaticResolver]:
 * every method returns the corresponding field on [targets] verbatim. For
 * unknown builds, MainHook wraps the *latest* known [TargetSet] in a
 * `FallbackResolver` that attempts dynamic discovery for symbols with a
 * stable runtime anchor (FQN-stable classes, named resource ids) and falls
 * back to the static [TargetSet] value on failure.
 *
 * Only symbols cheaply discoverable at runtime appear here. R8-renamed
 * method names have no stable anchor and remain on [TargetSet].
 */
interface TargetResolver {
    /** The [TargetSet] this resolver wraps. Always the *latest known* set in fallback mode. */
    val targets: TargetSet

    /** True if MainHook found an exact versionCode match in [Targets.knownVersions]. */
    val exact: Boolean

    /**
     * Truth Social's `Feed` data class. R8 leaves the FQN intact because
     * the class is serialized via Moshi (`@JsonClass(generateAdapter = true)`),
     * but the package moved from `com.truthsocial.app.data.models.feeds`
     * (≤ v1.26.1) to `com.truthsocial.core.data.models.feeds` (v1.26.2+).
     * Fallback mode tries both.
     */
    fun resolveFeedClass(): String

    /**
     * Truth Social's `Features` data class. Same package migration story as
     * [resolveFeedClass]; moved from `app.*` to `core.*` in v1.26.2.
     */
    fun resolveFeaturesClass(): String

    /**
     * Resolve a string resource id by name. The integer drifts every release
     * (aapt re-numbers when strings are added/removed); the symbolic name
     * (`R.string.help_center`) is stable. Fallback mode uses
     * `Resources.getIdentifier` for a dynamic lookup; static mode returns
     * the precomputed id from [TargetSet] without touching `Resources`.
     */
    fun resolveStringResId(
        name: String,
        staticFallback: Int,
    ): Int

    /**
     * Route id (`"alerts"`, `"feeds"`) → loaded tab class, used by
     * [com.example.mtga.hooks.BottomBarReorderHook]. Tabs are singleton
     * objects in a tight sibling package (`Zc.*` / `cd.*`); static mode
     * pulls the map from [TargetSet.bottomNavTabClasses]. Fallback mode
     * additionally walks a guessed neighbour package and reads each
     * candidate's `b()` route, so a freshly-renamed but structurally
     * identical build keeps the reorder feature working.
     */
    fun resolveBottomBarTabClasses(): Map<String, Class<*>>
}
