package com.example.mtga

import android.content.Context
import com.example.mtga.common.TargetResolver
import com.example.mtga.common.TargetSet

/**
 * Resolver for builds whose versionCode is not in [Targets.knownVersions].
 * Wraps the latest known [TargetSet] and tries dynamic discovery for symbols
 * with stable runtime anchors (FQN-stable classes, named resources, route
 * singletons). Discovery failures fall back to the static [TargetSet] value,
 * so this mode is strictly additive over `StaticResolver`.
 */
class FallbackResolver(
    override val targets: TargetSet,
    private val classLoader: ClassLoader,
    private val context: Context,
) : TargetResolver {
    override val exact: Boolean = false

    override fun resolveFeedClass(): String =
        firstLoadable(
            listOf(
                targets.feedClass.name,
                "com.truthsocial.core.data.models.feeds.Feed",
                "com.truthsocial.app.data.models.feeds.Feed",
            ),
        ) ?: targets.feedClass.name

    override fun resolveFeaturesClass(): String =
        firstLoadable(
            listOf(
                targets.featuresClass.name,
                "com.truthsocial.core.data.models.Features",
                "com.truthsocial.app.data.models.Features",
            ),
        ) ?: targets.featuresClass.name

    override fun resolveStringResId(
        name: String,
        staticFallback: Int,
    ): Int {
        val id = runCatching { context.resources.getIdentifier(name, "string", context.packageName) }.getOrDefault(0)
        return if (id != 0) id else staticFallback
    }

    /**
     * Build the route → tab-class map. Try the static [TargetSet] entries
     * first, then probe the package these tabs live in (single-letter
     * siblings whose `b()` returns the route literal). If every probe fails,
     * return whatever we resolved statically (possibly empty; in that case
     * [com.example.mtga.hooks.BottomBarReorderHook] declines to install).
     */
    override fun resolveBottomBarTabClasses(): Map<String, Class<*>> {
        val staticResolved =
            targets.bottomNavTabClasses
                .mapNotNull { (route, target) ->
                    runCatching { route to classLoader.loadClass(target.name) }.getOrNull()
                }.toMap()
        if (staticResolved.size == targets.bottomNavTabClasses.size) return staticResolved

        val discovered = discoverTabsInSiblingPackage()
        if (discovered.isEmpty()) return staticResolved

        // Static results win when both sources name the same route (they're
        // human-verified); discovery fills the gaps.
        return discovered + staticResolved
    }

    /**
     * Walk single-letter classes (`a`..`z`) in the package hosting
     * [TargetSet.bottomNavTabs]. Each tab subclass is a Kotlin `object` with
     * a static field `a` and an instance method `b()` returning the route
     * string. Reflection failures on any candidate are absorbed so a
     * misshapen class doesn't break discovery for sibling tabs.
     */
    private fun discoverTabsInSiblingPackage(): Map<String, Class<*>> {
        val tabsPackage = targets.bottomNavTabs.name.substringBeforeLast('.', missingDelimiterValue = "")
        if (tabsPackage.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Class<*>>()
        for (ch in 'a'..'z') {
            val fqn = "$tabsPackage.$ch"
            val cls = runCatching { classLoader.loadClass(fqn) }.getOrNull() ?: continue
            val singleton = runCatching { cls.getField("a").get(null) }.getOrNull() ?: continue
            val route = runCatching { cls.getMethod("b").invoke(singleton) as? String }.getOrNull() ?: continue
            result[route] = cls
        }
        return result
    }

    private fun firstLoadable(candidates: List<String>): String? =
        candidates.firstNotNullOfOrNull { fqn ->
            runCatching {
                classLoader.loadClass(fqn)
                fqn
            }.getOrNull()
        }
}
