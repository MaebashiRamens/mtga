package com.example.mtga.hooks.preferences

import android.content.Intent
import android.content.SharedPreferences
import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetResolver
import com.example.mtga.common.TargetSet
import com.example.mtga.config.SettingsHolder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.ArrayList

/**
 * Modern (v1.26.2+) Preferences-screen injector.
 *
 * v1.26.2 rebuilt the Preferences screen as a Compose-only flow under
 * `na.*` / `oa.*`. A top-level file-class function (`na.j.p` on v1.26.2,
 * `oa.k.p` on v1.27.0) receives the screen root (`Ud.g` / `Zd.f`) and
 * appends section data objects (`Ud.b` / `Zd.b`) to its `ArrayList` field.
 * Each section collects clickable text rows (`Ud.d` / `Zd.d`) whose last
 * field is a Kotlin `Function0` invoked when the row is tapped.
 *
 * Strategy: after-hook the screen builder, take the root's section list,
 * append an "MTGA" section with a single "MTGA Settings" row whose onClick
 * launches [com.example.mtga.SettingsActivity].
 *
 * Robustness:
 *  - Field lookups go by type (SharedPreferences / String / ArrayList /
 *    Function0) and declaration order, never by name. R8 renames fields on
 *    every build; declaration order is preserved.
 *  - All operations are `runCatching`-wrapped so a calibration mismatch
 *    can't crash the host. Worst case: the MTGA row doesn't appear and the
 *    triple-tap fallback from [com.example.mtga.hooks.InAppSettingsHook]
 *    stays available.
 *  - Idempotent under repeated calls; the [WeakHashMap] guard skips append
 *    when a given root instance has already been augmented.
 */
class ModernPreferencesInjector : PreferencesInjector {
    override val name = "ModernPreferencesInjector"

    /**
     * Tracks `Zd.f` / `Ud.g` instances we've already injected into. The
     * screen builder may run multiple times (recomposition, re-entry); an
     * identity-hash set keyed by root instance ensures one MTGA row at most.
     */
    private val seen: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    override fun install(
        resolver: TargetResolver,
        classLoader: ClassLoader,
    ) {
        val targets = resolver.targets
        val builderTarget = targets.modernPreferencesBuilder
        if (builderTarget == null) {
            XposedBridge.log(
                "[$TAG] $name: no modern preferences builder configured for " +
                    "${targets.buildId.versionName} — triple-tap fallback remains active.",
            )
            return
        }

        val builderClass =
            try {
                XposedHelpers.findClass(builderTarget.name, classLoader)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] $name: builder class ${builderTarget.name} not found: ${t.message}")
                return
            }

        XposedBridge.hookAllMethods(
            builderClass,
            targets.modernPreferencesBuilderMethod,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.args.getOrNull(0) ?: return
                    val rootClass = targets.modernPreferencesRoot ?: return
                    // Sanity check: the first arg must be the screen root
                    // we expect. Other Composable file-classes can have a
                    // `p()` method too.
                    val expected =
                        try {
                            XposedHelpers.findClass(rootClass.name, classLoader)
                        } catch (_: Throwable) {
                            return
                        }
                    if (!expected.isInstance(root)) return
                    synchronized(seen) {
                        if (!seen.add(root)) return
                    }
                    runCatching { appendMtgaSection(classLoader, targets, root) }
                        .onFailure {
                            XposedBridge.log("[$TAG] $name: append failed: ${it.message}")
                            // Allow a later attempt; the failure may be
                            // transient (e.g. class not yet loaded).
                            synchronized(seen) { seen.remove(root) }
                        }
                }
            },
        )
        XposedBridge.log(
            "[$TAG] $name: hook installed on ${builderTarget.name}." +
                "${targets.modernPreferencesBuilderMethod}",
        )
    }

    private fun appendMtgaSection(
        classLoader: ClassLoader,
        targets: TargetSet,
        root: Any,
    ) {
        val sectionTarget =
            targets.modernPreferencesSection
                ?: error("modernPreferencesSection not configured")
        val rowTarget =
            targets.modernPreferencesTextRow
                ?: error("modernPreferencesTextRow not configured")
        val function0Target =
            targets.modernKotlinFunction0
                ?: error("modernKotlinFunction0 not configured")

        val rootClass = root.javaClass
        // Zd.f / Ud.g: 2× SharedPreferences + 1× ArrayList.
        val appPrefs = findFieldByType(rootClass, SharedPreferences::class.java, 0).get(root)!!
        val userPrefs = findFieldByType(rootClass, SharedPreferences::class.java, 1).get(root)!!

        @Suppress("UNCHECKED_CAST")
        val sectionsList =
            findFieldByType(rootClass, ArrayList::class.java, 0).get(root) as MutableList<Any>

        val sectionClass = XposedHelpers.findClass(sectionTarget.name, classLoader)
        val section =
            XposedHelpers.newInstance(
                sectionClass,
                arrayOf<Class<*>>(SharedPreferences::class.java, SharedPreferences::class.java),
                appPrefs,
                userPrefs,
            )
        // Zd.b / Ud.b: c=String title, e=ArrayList items.
        findFieldByType(sectionClass, String::class.java, 0).set(section, "MTGA")
        @Suppress("UNCHECKED_CAST")
        val items =
            findFieldByType(sectionClass, ArrayList::class.java, 0).get(section) as MutableList<Any>

        val row =
            buildMtgaRow(
                classLoader,
                targets,
                rowTarget.name,
                function0Target.name,
                appPrefs,
                userPrefs,
            )
        items.add(row)
        sectionsList.add(section)
        XposedBridge.log(
            "[$TAG] $name: appended MTGA section (sections=${sectionsList.size}, items=${items.size})",
        )
    }

    private fun buildMtgaRow(
        classLoader: ClassLoader,
        targets: TargetSet,
        rowClassName: String,
        function0ClassName: String,
        appPrefs: Any,
        userPrefs: Any,
    ): Any {
        val rowClass = XposedHelpers.findClass(rowClassName, classLoader)
        // Zd.d / Ud.d ctor is `(SharedPreferences, SharedPreferences, h0/s0)`.
        // The third arg is a no-op formatter; substitute a Proxy.
        val ctor =
            rowClass.declaredConstructors.firstOrNull { it.parameterCount == 3 }
                ?: error("$rowClassName: 3-arg ctor missing")
        val thirdType = ctor.parameterTypes[2]
        val thirdArg: Any =
            when {
                thirdType.isInterface -> {
                    newInterfaceProxy(classLoader, thirdType, "MTGANoopFormatter") { _, _ -> null }
                }

                else -> {
                    instantiateBenign(thirdType)
                }
            }
        ctor.isAccessible = true
        val row = ctor.newInstance(appPrefs, userPrefs, thirdArg)
        // String idx 0 = title, String idx 1 = subtitle.
        findFieldByType(rowClass, String::class.java, 0).set(row, "MTGA Settings")
        runCatching {
            findFieldByType(rowClass, String::class.java, 1).set(row, "Open MTGA settings activity")
        }

        // The onClick field is the only one typed as the R8-renamed
        // Function0 interface (Zd.d.k / Ud.d.k).
        val function0Class = XposedHelpers.findClass(function0ClassName, classLoader)
        val clickField = findFieldByType(rowClass, function0Class, 0)
        clickField.set(row, buildClickHandler(classLoader, function0Class))
        return row
    }

    private fun buildClickHandler(
        classLoader: ClassLoader,
        function0Class: Class<*>,
    ): Any =
        newInterfaceProxy(classLoader, function0Class, "MTGAClickHandler") { method, _ ->
            if (method.name == "invoke") launchMtgaSettings()
            null
        }

    private fun launchMtgaSettings() {
        val ctx =
            SettingsHolder.appContext() ?: run {
                XposedBridge.log("[$TAG] $name: no host context yet, cannot launch settings")
                return
            }
        val intent =
            Intent().apply {
                setClassName("com.example.mtga", "com.example.mtga.SettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        runCatching { ctx.startActivity(intent) }
            .onFailure { XposedBridge.log("[$TAG] $name: startActivity failed: ${it.message}") }
    }

    /**
     * Instantiate a benign placeholder of a concrete class. Prefers the
     * no-arg ctor; the formatter is only stored, not invoked, so any
     * non-null instance works.
     */
    private fun instantiateBenign(type: Class<*>): Any {
        val noArg = type.declaredConstructors.firstOrNull { it.parameterCount == 0 }
        if (noArg != null) {
            noArg.isAccessible = true
            return noArg.newInstance()
        }
        // Smallest ctor with primitive defaults.
        val ctor =
            type.declaredConstructors.minByOrNull { it.parameterCount }
                ?: error("${type.name}: no usable ctor for benign instance")
        ctor.isAccessible = true
        val args: Array<Any?> =
            ctor.parameterTypes
                .map { p ->
                    when (p) {
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Boolean.TYPE -> false
                        java.lang.Byte.TYPE -> 0.toByte()
                        java.lang.Short.TYPE -> 0.toShort()
                        java.lang.Float.TYPE -> 0f
                        java.lang.Double.TYPE -> 0.0
                        java.lang.Character.TYPE -> 0.toChar()
                        else -> null
                    } as Any?
                }.toTypedArray()
        return ctor.newInstance(*args)
    }

    /**
     * Find the [occurrence]-th declared field on [clazz] whose type is
     * [type] or a subtype. Field declaration order is preserved by R8 even
     * when field names are renamed.
     */
    private fun findFieldByType(
        clazz: Class<*>,
        type: Class<*>,
        occurrence: Int,
    ): Field {
        var seen = 0
        for (field in clazz.declaredFields) {
            if (type.isAssignableFrom(field.type)) {
                if (seen == occurrence) {
                    field.isAccessible = true
                    return field
                }
                seen++
            }
        }
        error("${clazz.name}: no field of type ${type.name} at occurrence $occurrence")
    }

    private fun newInterfaceProxy(
        classLoader: ClassLoader,
        iface: Class<*>,
        label: String,
        onInvoke: (java.lang.reflect.Method, Array<out Any?>?) -> Any?,
    ): Any =
        Proxy.newProxyInstance(classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> label
                else -> onInvoke(method, args)
            }
        }
}
