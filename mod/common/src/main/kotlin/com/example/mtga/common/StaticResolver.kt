package com.example.mtga.common

/**
 * Resolver for builds whose versionCode matches [Targets.knownVersions]
 * exactly. Every call returns the precomputed value from [targets] with no
 * class scanning, reflection, or resource lookup. Preserves the mod's
 * behaviour from before the resolver layer existed.
 */
class StaticResolver(
    override val targets: TargetSet,
    private val classLoader: ClassLoader,
) : TargetResolver {
    override val exact: Boolean = true

    override fun resolveFeedClass(): String = targets.feedClass.name

    override fun resolveFeaturesClass(): String = targets.featuresClass.name

    override fun resolveStringResId(
        name: String,
        staticFallback: Int,
    ): Int = staticFallback

    override fun resolveBottomBarTabClasses(): Map<String, Class<*>> =
        targets.bottomNavTabClasses
            .mapNotNull { (route, target) ->
                runCatching { route to classLoader.loadClass(target.name) }.getOrNull()
            }.toMap()
}
