package com.example.mtga.hooks

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.example.mtga.MainHook.Companion.TAG
import com.example.mtga.common.TargetSet
import com.example.mtga.config.SettingsHolder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.ArrayList

/**
 * Inject an "MTGA Settings" row into Truth Social's own Preferences screen.
 *
 * Truth Social builds the Preferences list via `sa.j.p(ic.f, ...)` — a private
 * static method that appends `ic.b` sections to an ArrayList field on the
 * `ic.f` root. Each section holds `ic.d` rows with a `Mc.a` (Function0) click
 * callback.
 *
 * We hook `sa.j.p` afterwards, then append our own section that launches the
 * MTGA SettingsActivity on click.
 *
 * Field names are NOT hard-coded — R8 obfuscation differs across builds and
 * jadx renames don't match the actual DEX names. Instead we look fields up by
 * type within each container class:
 *   - ic.f  → 2× SharedPreferences (app, user) + 1× ArrayList (sections)
 *   - ic.b  → 2× SharedPreferences + 1× String (title) + 1× ArrayList (items)
 *   - ic.d  → 2× SharedPreferences + 2× String (title, subtitle) + 1× Mc.a (click)
 *
 * The click handler is a `java.lang.reflect.Proxy` implementing the host's
 * Mc.a interface; our MTGA-side `kotlin.jvm.functions.Function0` is a different
 * class from the host's R8-renamed `Mc.a` and can't be substituted directly.
 */
class TruthSocialPreferencesHook(
    targets: TargetSet,
) : BaseHook(targets) {
    override val name = "TruthSocialPreferences"

    override fun hook(classLoader: ClassLoader) {
        val builderClass = XposedHelpers.findClass(targets.preferencesBuilder.name, classLoader)
        XposedBridge.hookAllMethods(
            builderClass,
            targets.preferencesBuilderMethod,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val prefsRoot = param.args.getOrNull(0) ?: return
                    runCatching { appendMtgaSection(classLoader, prefsRoot) }
                        .onFailure { XposedBridge.log("[$TAG] $name: append failed: ${it.message}") }
                }
            },
        )
        XposedBridge.log("[$TAG] $name: prefs-screen hook installed")
    }

    private fun appendMtgaSection(
        classLoader: ClassLoader,
        prefsRoot: Any,
    ) {
        val rootClass = prefsRoot.javaClass
        val sectionsField = findFieldByType(rootClass, ArrayList::class.java, 0)
        val appPrefsField = findFieldByType(rootClass, SharedPreferences::class.java, 0)
        val userPrefsField = findFieldByType(rootClass, SharedPreferences::class.java, 1)

        @Suppress("UNCHECKED_CAST")
        val sectionsList = sectionsField.get(prefsRoot) as MutableList<Any>
        val appPrefs = appPrefsField.get(prefsRoot)!!
        val userPrefs = userPrefsField.get(prefsRoot)!!

        val section = newSection(classLoader, appPrefs, userPrefs)
        val sectionTitleField = findFieldByType(section.javaClass, String::class.java, 0)
        sectionTitleField.set(section, "MTGA")

        @Suppress("UNCHECKED_CAST")
        val sectionItems =
            findFieldByType(section.javaClass, ArrayList::class.java, 0)
                .get(section) as MutableList<Any>
        sectionItems.add(buildMtgaRow(classLoader, appPrefs, userPrefs))

        sectionsList.add(section)
        XposedBridge.log("[$TAG] $name: injected MTGA section (now ${sectionsList.size} total)")
    }

    private fun newSection(
        classLoader: ClassLoader,
        appPrefs: Any,
        userPrefs: Any,
    ): Any {
        val sectionClass = XposedHelpers.findClass(targets.preferencesSection.name, classLoader)
        return XposedHelpers.newInstance(
            sectionClass,
            arrayOf<Class<*>>(SharedPreferences::class.java, SharedPreferences::class.java),
            appPrefs,
            userPrefs,
        )
    }

    private fun buildMtgaRow(
        classLoader: ClassLoader,
        appPrefs: Any,
        userPrefs: Any,
    ): Any {
        val rowClass = XposedHelpers.findClass(targets.preferencesTextRow.name, classLoader)
        // ic.d's 3-arg ctor: third param is decompiled as a concrete `d0.r2` in
        // jadx but declared as `Mc.l` in DEX (it's only stored, not called). We
        // adapt to whatever 3rd type the actual ctor declares.
        val ctor =
            rowClass.declaredConstructors.firstOrNull { it.parameterCount == 3 }
                ?: error("${targets.preferencesTextRow.name} 3-arg ctor missing")
        val thirdType = ctor.parameterTypes[2]
        val thirdArg: Any =
            if (thirdType.isInterface) {
                newInterfaceProxy(classLoader, thirdType, "MTGANoopFormatter") { _, _ -> hostUnit(classLoader) }
            } else {
                // Concrete switch-impl class (e.g. d0.r2 with `(int)` ctor). Pick a
                // no-op variant — index 22 maps to a `return Unit` branch.
                XposedHelpers.newInstance(thirdType, arrayOf<Class<*>>(Integer.TYPE), 22)
            }
        ctor.isAccessible = true
        val row = ctor.newInstance(appPrefs, userPrefs, thirdArg)

        val titleField = findFieldByType(rowClass, String::class.java, 0)
        titleField.set(row, "MTGA Settings")

        val function0Class = XposedHelpers.findClass(targets.kotlinFunction0.name, classLoader)
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
            hostUnit(classLoader)
        }

    private fun hostUnit(classLoader: ClassLoader): Any {
        val unitClass = XposedHelpers.findClass(targets.kotlinUnit.name, classLoader)
        // The kotlin.Unit singleton is the only static field on the class.
        val singletonField =
            unitClass.declaredFields.firstOrNull {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.type == unitClass
            } ?: error("${unitClass.name}: no static singleton field")
        singletonField.isAccessible = true
        return singletonField.get(null)!!
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
     * Find the [occurrence]-th declared field on [clazz] whose type is exactly
     * [type] (or a subtype). Resilient to R8 single-letter rename — we only
     * care about declaration order, which R8 preserves.
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

    /**
     * Proxy that responds to one declared interface method via [onInvoke] and
     * delegates [Object.equals]/[Object.hashCode]/[Object.toString] to sane
     * defaults so the host can put the proxy into HashMaps, log it, etc.
     */
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
