package com.example.mtga.hooks

import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetResolver
import com.example.mtga.config.Settings
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Reorder the bottom navigation bar's tab list (v1.26.2+).
 *
 * Truth Social's v1.26.2 Compose-Nav rewrite moved the tab list off an
 * instance method onto static fields on a single holder class
 * ([TargetSet.bottomNavTabs]): one list per app variant, currently
 * `a` (predictions-enabled) and `b` (chats-enabled).
 *
 * Each static field holds a Kotlin `listOf(...)` of singleton tab objects
 * (instances of [TargetSet.bottomNavTabClasses] values, keyed by route id).
 * We:
 *   1. Force <clinit> by loading the class.
 *   2. Read each static field as `List<Any>`.
 *   3. Tag every entry with its route id; unknown entries keep their
 *      original relative order at the tail.
 *   4. Reorder per the user's pref, write back via
 *      [XposedHelpers.setStaticObjectField].
 *
 * Compose reads the list when composing the BottomBar; replacing the static
 * slot before any composition runs ensures the UI picks up the new order.
 * Static fields aren't `final` after R8 minification (they were
 * `companion object` properties in Kotlin source).
 *
 * Silently no-ops on v1.26.1 and earlier; the dynamic-list shape there is
 * not supported.
 */
class BottomBarReorderHook(
    resolver: TargetResolver,
) : BaseHook(resolver) {
    override val name = "BottomBarReorder"

    override fun hook(classLoader: ClassLoader) {
        val staticFields = targets.bottomNavTabsStaticFields
        if (staticFields.isEmpty()) {
            XposedBridge.log("[$TAG] BottomBarReorder skipped — current build has no static tab list")
            return
        }

        // Resolve the route→class map through the resolver. StaticResolver
        // returns targets.bottomNavTabClasses loaded; FallbackResolver also
        // probes neighbour single-letter classes for tabs added in a fresh
        // uncalibrated build.
        val routeToClass = resolver.resolveBottomBarTabClasses()
        if (routeToClass.isEmpty()) {
            XposedBridge.log("[$TAG] BottomBarReorder skipped — no tab classes resolved")
            return
        }

        val orderString = Settings.getString(SettingKeys.BottomBarTabOrder, SettingKeys.DefaultBottomBarTabOrder)
        val preferredOrder =
            orderString.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (preferredOrder.isEmpty()) {
            XposedBridge.log("[$TAG] BottomBarReorder skipped — empty order pref")
            return
        }

        val tabsClass = XposedHelpers.findClass(targets.bottomNavTabs.name, classLoader)
        // Trigger <clinit> so the static fields are populated before we read them.
        try {
            Class.forName(targets.bottomNavTabs.name, true, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] BottomBarReorder: <clinit> trigger failed: ${t.message}")
            return
        }

        for (fieldName in staticFields) {
            reorderField(tabsClass, fieldName, routeToClass, preferredOrder)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun reorderField(
        tabsClass: Class<*>,
        fieldName: String,
        routeToClass: Map<String, Class<*>>,
        preferredOrder: List<String>,
    ) {
        val current =
            try {
                XposedHelpers.getStaticObjectField(tabsClass, fieldName) as? List<Any>
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] BottomBarReorder: read ${tabsClass.name}.$fieldName failed: ${t.message}")
                return
            } ?: return

        // Tag every element with its route id (null if unmatched).
        val tagged: List<Pair<String?, Any>> =
            current.map { tab ->
                val route =
                    routeToClass.entries.firstOrNull { (_, cls) -> cls.isInstance(tab) }?.key
                route to tab
            }

        val byRoute = tagged.filter { it.first != null }.associateBy { it.first!! }
        val unknown = tagged.filter { it.first == null }.map { it.second }
        val reordered: List<Any> =
            buildList {
                // Strict-include: only tabs the user listed appear, in the
                // listed order. Known routes the user omitted are dropped;
                // they can be re-added via the "+ route" chips in MTGA
                // Settings.
                for (route in preferredOrder) {
                    byRoute[route]?.let { add(it.second) }
                }
                // Preserve unknown tabs at the tail. Truth Social may add
                // new routes in a future build we haven't calibrated, and
                // we don't want a stale preferred-order string to hide them.
                addAll(unknown)
            }

        val originalRoutes = tagged.map { it.first ?: "?" }
        val newRoutes = reordered.map { tab -> routeToClass.entries.firstOrNull { it.value.isInstance(tab) }?.key ?: "?" }

        if (reordered == current) {
            XposedBridge.log("[$TAG] BottomBarReorder: $fieldName already in desired order ($originalRoutes)")
            return
        }

        try {
            XposedHelpers.setStaticObjectField(tabsClass, fieldName, reordered)
            XposedBridge.log("[$TAG] BottomBarReorder: $fieldName  $originalRoutes -> $newRoutes")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] BottomBarReorder: write ${tabsClass.name}.$fieldName failed: ${t.message}")
        }
    }
}
