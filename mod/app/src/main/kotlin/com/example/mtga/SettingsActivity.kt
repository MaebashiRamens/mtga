package com.example.mtga

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.mtga.common.FeatureOverride
import com.example.mtga.common.PremiumMode
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.Targets
import com.example.mtga.config.FeatureOverrideEntry
import com.example.mtga.config.PremiumModeEntry
import com.example.mtga.config.SettingItem
import com.example.mtga.config.Settings
import com.example.mtga.config.SettingsCategory
import com.example.mtga.config.Toggle

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Discard saved RadioButton/Switch state from any previous instance.
        // We re-initialize from the persisted prefs file; stale view-state
        // restoration was rendering radios with the wrong selection.
        super.onCreate(null)
        title = "MTGA settings"
        setContentView(buildContentView())
    }

    /**
     * Stamp [SettingKeys.RestartMarker] with the current wall-clock time so
     * [com.example.mtga.hooks.HostRestartHook] can detect that the user
     * finished editing settings. The hook reads this on every
     * `Activity.onResume`; when it differs from the value cached at process
     * start, the process kills itself and Android respawns Truth Social with
     * the new preferences.
     *
     * Triggered from [onStop] (not [onPause]) so a transient pause — config
     * change, system overlay — doesn't request a host restart.
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing || !isChangingConfigurations) {
            val prefs = openWorldReadablePrefs()
            prefs.edit().putLong(SettingKeys.RestartMarker, System.currentTimeMillis()).apply()
            android.util.Log.i("MTGA-Settings", "onStop: bumped restart marker")
        }
    }

    private fun buildContentView(): ScrollView {
        val prefs = openWorldReadablePrefs()
        runPremiumDefaultMigration(prefs)
        // Resolve the TargetSet for the installed Truth Social build so we
        // can hide toggles that would be guaranteed no-ops. If Truth Social
        // isn't installed, fall back to `Targets.latest`.
        val activeTargets = resolveInstalledTargetSet()
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

        column.addView(
            header(
                "MTGA — Truth Social Mod",
                "Toggles take effect after the host app restarts.\n" +
                    "Calibrated for Truth Social ${activeTargets.buildId.versionName} " +
                    "(${activeTargets.buildId.versionCode}).",
            ),
        )

        Settings.categories.forEachIndexed { index, category ->
            val visibleItems = category.items.filter { isItemSupported(it, activeTargets) }
            if (visibleItems.isEmpty()) return@forEachIndexed
            if (index > 0) column.addView(divider())
            column.addView(sectionTitle(category.title))
            for (item in visibleItems) {
                when (item) {
                    is SettingItem.Bool -> {
                        column.addView(toggleRow(item.toggle, prefs))
                        // Render the reorder UI under the ReorderBottomBar
                        // toggle so it's discoverable.
                        if (item.toggle.key == SettingKeys.ReorderBottomBar) {
                            column.addView(bottomBarReorderUi(prefs))
                        }
                    }

                    is SettingItem.Mode -> {
                        column.addView(premiumModeRow(item.entry, prefs))
                    }

                    is SettingItem.Override -> {
                        column.addView(featureOverrideRow(item.entry, prefs))
                    }
                }
            }
        }

        return ScrollView(this).apply {
            addView(column, ViewParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun isItemSupported(
        item: SettingItem,
        targets: com.example.mtga.common.TargetSet,
    ): Boolean =
        when (item) {
            is SettingItem.Bool -> item.toggle.supportedFor(targets)
            is SettingItem.Mode -> item.entry.supportedFor(targets)
            is SettingItem.Override -> item.entry.supportedFor(targets)
        }

    /**
     * Find the [com.example.mtga.common.TargetSet] for the Truth Social
     * build installed on this device. We can't query the host directly
     * (different process); `PackageManager` exposes the versionCode of any
     * installed package. If Truth Social is missing or the versionCode isn't
     * calibrated, fall back to `Targets.latest` so every toggle stays
     * visible (the hook itself no-ops gracefully via
     * [com.example.mtga.FallbackResolver]).
     */
    private fun resolveInstalledTargetSet(): com.example.mtga.common.TargetSet {
        val pm = packageManager
        val pi =
            runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo("com.truthsocial.android.app", 0)
            }.getOrNull() ?: return Targets.latest
        @Suppress("DEPRECATION")
        val installedCode = pi.versionCode
        return Targets.forVersionCode(installedCode) ?: Targets.latest
    }

    /**
     * Editable bottom-bar tab-order widget with native drag-and-drop.
     *
     * Each row has a drag handle on the left and a delete (×) button on the
     * right. Long-pressing the row body or the handle starts a system
     * drag-and-drop ([View.startDragAndDrop]); the rows container is the
     * drop target, computing the insertion index from the drop Y coordinate.
     *
     * Order is persisted to [SettingKeys.BottomBarTabOrder] as a
     * comma-separated string; the BottomBarReorderHook re-reads it on the
     * next host-app start.
     *
     * Available route ids come from [com.example.mtga.common.TargetSet.bottomNavTabClasses]
     * on the latest known set (v1.27.0: feeds / discover / groups / alerts /
     * chats / predictions). Routes not in the order appear as chips below
     * the list and append on tap.
     */
    private fun bottomBarReorderUi(prefs: SharedPreferences): LinearLayout {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), 0, dp(8))
            }
        box.addView(
            TextView(this).apply {
                text = "Bottom bar order — drag rows to reorder (top = leftmost tab)"
                textSize = 13f
            },
        )
        // Truth Social picks Messages OR Predictions based on a server
        // feature flag, never both. Surfacing this explicitly avoids
        // "I added it but it didn't appear" confusion.
        box.addView(
            TextView(this).apply {
                text =
                    "Note: Messages (chats) and Predictions are mutually " +
                        "exclusive in Truth Social — only whichever Truth " +
                        "Social itself picks at runtime will actually appear."
                textSize = 11f
                setPadding(0, dp(4), 0, dp(4))
                alpha = 0.7f
            },
        )

        val knownRoutes =
            Targets.latest.bottomNavTabClasses.keys
                .toList()
        val list = readOrder(prefs).toMutableList()

        // Drop persisted routes that no longer exist in the calibrated tab
        // set (defensive against pref-file drift between releases).
        list.retainAll(knownRoutes.toSet())
        if (list.isEmpty()) list.addAll(SettingKeys.DefaultBottomBarTabOrder.split(','))

        val rowsContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
        val chipsContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
        box.addView(rowsContainer)
        box.addView(
            TextView(this).apply {
                text = "Available routes (tap to append):"
                textSize = 12f
                setPadding(0, dp(8), 0, dp(4))
            },
        )
        box.addView(chipsContainer)

        fun persist() {
            prefs.edit().putString(SettingKeys.BottomBarTabOrder, list.joinToString(",")).commit()
        }

        // Forward-declared so row builders can call rebuild() via this holder.
        val rebuilder = arrayOfNulls<() -> Unit>(1)

        // Drag-and-drop: tracks the source row so the drop handler can
        // identify it and restore visibility on cancel.
        var draggingIndex: Int = -1

        rowsContainer.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    true
                }

                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    true
                }

                DragEvent.ACTION_DROP -> {
                    val target = computeTargetIndex(rowsContainer, event.y)
                    val src = draggingIndex
                    if (src in list.indices && target in 0..list.size && src != target && src != target - 1) {
                        val item = list.removeAt(src)
                        val insertAt = if (target > src) target - 1 else target
                        list.add(insertAt.coerceIn(0, list.size), item)
                        persist()
                        rebuilder[0]?.invoke()
                    }
                    true
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    draggingIndex = -1
                    // Restore visibility in case the drop landed outside.
                    for (i in 0 until rowsContainer.childCount) {
                        rowsContainer.getChildAt(i).alpha = 1f
                    }
                    true
                }

                else -> {
                    true
                }
            }
        }

        fun rebuild() {
            rowsContainer.removeAllViews()
            chipsContainer.removeAllViews()

            for ((index, route) in list.withIndex()) {
                val rowView =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        background = rowBackground()
                        layoutParams =
                            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                                bottomMargin = dp(6)
                            }
                    }
                val handle =
                    TextView(this).apply {
                        text = "⋮⋮"
                        textSize = 20f
                        setPadding(dp(4), 0, dp(12), 0)
                    }
                rowView.addView(handle)
                rowView.addView(
                    TextView(this).apply {
                        text = "${index + 1}. ${routeLabel(route)}"
                        textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    },
                )
                rowView.addView(
                    Button(this).apply {
                        text = "×"
                        minimumWidth = dp(48)
                        setOnClickListener {
                            list.removeAt(index)
                            persist()
                            rebuilder[0]?.invoke()
                        }
                    },
                )

                val startDrag =
                    View.OnLongClickListener {
                        draggingIndex = index
                        rowView.alpha = 0.4f
                        val shadow = View.DragShadowBuilder(rowView)
                        // ClipData is required even when only positional
                        // info is needed; supply a sentinel label.
                        val clip = ClipData.newPlainText("mtga.tab", route)
                        rowView.startDragAndDrop(clip, shadow, route, 0)
                        true
                    }
                rowView.setOnLongClickListener(startDrag)
                handle.setOnLongClickListener(startDrag)

                rowsContainer.addView(rowView)
            }

            // Routes in the calibrated set but not in the user's order.
            // Tap to append at the tail.
            val missing = knownRoutes.filter { it !in list }
            if (missing.isEmpty()) {
                chipsContainer.addView(
                    TextView(this).apply {
                        text = "(all routes included)"
                        textSize = 12f
                    },
                )
            } else {
                for (route in missing) {
                    chipsContainer.addView(
                        Button(this).apply {
                            text = "+ ${routeLabel(route)}"
                            setOnClickListener {
                                list.add(route)
                                persist()
                                rebuilder[0]?.invoke()
                            }
                        },
                    )
                }
            }
        }

        rebuilder[0] = ::rebuild
        rebuild()
        return box
    }

    /**
     * Drop-insertion index from a Y coordinate inside the rows container.
     * 0 = before the first row, `count` = after the last.
     */
    private fun computeTargetIndex(
        container: LinearLayout,
        yPx: Float,
    ): Int {
        var index = container.childCount
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val mid = child.y + child.height / 2f
            if (yPx < mid) {
                index = i
                break
            }
        }
        return index
    }

    private fun rowBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(0x22FFFFFF) // subtle pill on dark background
            setStroke(dp(1), Color.argb(40, 255, 255, 255))
        }

    /**
     * Human-readable label for a route id. The route id (`"feeds"`,
     * `"alerts"`) survives R8 and is what we persist, but the user sees the
     * host's tab title, so the reorder UI maps to that.
     */
    private fun routeLabel(route: String): String =
        when (route) {
            "feeds" -> "Home"
            "discover" -> "Discover"
            "groups" -> "Groups"
            "chats" -> "Messages"
            "predictions" -> "Predictions"
            "alerts" -> "Alerts"
            else -> route
        }

    private fun readOrder(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(SettingKeys.BottomBarTabOrder, null) ?: SettingKeys.DefaultBottomBarTabOrder
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun header(
        title: String,
        subtitle: String,
    ): LinearLayout {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(16))
            }
        box.addView(
            TextView(this).apply {
                text = title
                textSize = 20f
                setPadding(0, 0, 0, dp(4))
            },
        )
        box.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 13f
            },
        )
        return box
    }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun divider(): View =
        View(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                }
            setBackgroundColor(0x33000000)
        }

    private fun toggleRow(
        toggle: Toggle,
        prefs: SharedPreferences,
    ): LinearLayout {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }

        val labels =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
        labels.addView(
            TextView(this).apply {
                text = toggle.label
                textSize = 16f
            },
        )
        labels.addView(
            TextView(this).apply {
                text = toggle.description
                textSize = 12f
                setSingleLine(false)
                ellipsize = TextUtils.TruncateAt.END
            },
        )

        val switch =
            Switch(this).apply {
                isChecked = prefs.getBoolean(toggle.key, toggle.defaultOn)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(toggle.key, checked).commit()
                }
            }

        row.addView(labels)
        row.addView(switch, ViewParams(WRAP_CONTENT, WRAP_CONTENT))
        return row
    }

    /**
     * Three-state radio: Default (show but locked) / Force-enable / Hide.
     *
     * Force-enable lies to the client about Truth+ status; the server can
     * still reject and using these features risks the account. A
     * confirmation dialog runs before persisting; the radio reverts if the
     * user backs out.
     */
    private fun premiumModeRow(
        entry: PremiumModeEntry,
        prefs: SharedPreferences,
    ): LinearLayout {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
        box.addView(
            TextView(this).apply {
                text = entry.label
                textSize = 16f
            },
        )
        box.addView(
            TextView(this).apply {
                text = entry.description
                textSize = 12f
            },
        )

        val stored = prefs.getString(entry.key, null)
        val current = if (stored == null) entry.defaultMode else PremiumMode.fromStorage(stored)
        var lastCommitted = current
        var suppressNextChange = false

        val group =
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                for (mode in PremiumMode.values()) {
                    val btn =
                        RadioButton(this@SettingsActivity).apply {
                            text =
                                when (mode) {
                                    PremiumMode.Default -> "Default"
                                    PremiumMode.ForceEnable -> "Force-enable"
                                    PremiumMode.Hide -> "Hide"
                                }
                            id = View.generateViewId()
                            setTag(mode)
                        }
                    addView(btn)
                    if (mode == current) check(btn.id)
                }
            }

        fun commit(mode: PremiumMode) {
            prefs.edit().putString(entry.key, mode.storageValue).commit()
            lastCommitted = mode
        }

        fun revertTo(mode: PremiumMode) {
            val targetId =
                (0 until group.childCount)
                    .map { group.getChildAt(it) as RadioButton }
                    .first { it.tag == mode }
                    .id
            suppressNextChange = true
            group.check(targetId)
        }

        // RadioGroup.check() can cascade-fire the listener (the unchecked
        // button's onChange + the new checked button's onChange both reach
        // the group), so suppress the next callback after revertTo and
        // ignore "no actual change" callbacks defensively.
        group.setOnCheckedChangeListener { _, checkedId ->
            if (suppressNextChange) {
                suppressNextChange = false
                return@setOnCheckedChangeListener
            }
            val mode =
                group.findViewById<RadioButton>(checkedId)?.tag as? PremiumMode
                    ?: entry.defaultMode
            if (mode == lastCommitted) return@setOnCheckedChangeListener
            if (mode == PremiumMode.ForceEnable) {
                showForceEnableWarning(entry, onConfirm = { commit(mode) }, onCancel = { revertTo(lastCommitted) })
            } else {
                commit(mode)
            }
        }

        box.addView(group)
        return box
    }

    /**
     * Three-state radio: Default (server-supplied) / Force ON / Force OFF.
     * Mirrors [premiumModeRow] but persists a [FeatureOverride] string and
     * drives the [com.example.mtga.hooks.FeatureFlagHook] per-field override
     * path. No risk-warning dialog; the field already has a cautionary
     * description and the user is opting into a manual flag flip.
     */
    private fun featureOverrideRow(
        entry: FeatureOverrideEntry,
        prefs: SharedPreferences,
    ): LinearLayout {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
        box.addView(
            TextView(this).apply {
                text = entry.label
                textSize = 16f
            },
        )
        box.addView(
            TextView(this).apply {
                text = entry.description
                textSize = 12f
            },
        )

        val current = FeatureOverride.fromStorage(prefs.getString(entry.key, null))

        val group =
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                for (mode in FeatureOverride.values()) {
                    val btn =
                        RadioButton(this@SettingsActivity).apply {
                            text =
                                when (mode) {
                                    FeatureOverride.Default -> "Default"
                                    FeatureOverride.ForceTrue -> "Force ON"
                                    FeatureOverride.ForceFalse -> "Force OFF"
                                }
                            id = View.generateViewId()
                            setTag(mode)
                        }
                    addView(btn)
                    if (mode == current) check(btn.id)
                }
            }

        group.setOnCheckedChangeListener { _, checkedId ->
            val mode =
                group.findViewById<RadioButton>(checkedId)?.tag as? FeatureOverride
                    ?: FeatureOverride.Default
            prefs.edit().putString(entry.key, mode.storageValue).commit()
        }

        box.addView(group)
        return box
    }

    private fun showForceEnableWarning(
        entry: PremiumModeEntry,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        var resolved = false
        AlertDialog
            .Builder(this)
            .setTitle("Force-enable ${entry.label.lowercase()}?")
            .setMessage(
                "This bypasses the client-side Truth+ check, but the Truth Social server can still reject the request — and may " +
                    "flag the account for trying to use a paid feature without a subscription.\n\n" +
                    "Only enable this if you understand the risk.",
            ).setPositiveButton("Enable anyway") { _, _ ->
                resolved = true
                onConfirm()
            }.setNegativeButton("Cancel") { _, _ ->
                resolved = true
                onCancel()
            }.setOnDismissListener { if (!resolved) onCancel() }
            .show()
    }

    /**
     * One-shot migration: clear leftover premium-mode values so the entry's
     * defaultMode (Hide) takes effect and any Force-enable choice now goes
     * through the risk-warning dialog. Runs once per install, gated by
     * [MIGRATION_PREMIUM_DEFAULT_KEY].
     */
    private fun runPremiumDefaultMigration(prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATION_PREMIUM_DEFAULT_KEY, false)) return
        prefs
            .edit()
            .remove(SettingKeys.PostEditMode)
            .remove(SettingKeys.PostScheduleMode)
            .putBoolean(MIGRATION_PREMIUM_DEFAULT_KEY, true)
            .commit()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ViewParams(
        width: Int,
        height: Int,
    ) = LinearLayout.LayoutParams(width, height)

    /**
     * MODE_WORLD_READABLE throws SecurityException on stock Android since
     * API 24. LSPosed v1.x+ hooks the StrictMode check away for Xposed
     * modules, so the call succeeds on a rooted+LSPosed install and the
     * write lands in LSPosed's managed prefs dir where [XSharedPreferences]
     * in the hooked process can read it. Fall back to MODE_PRIVATE if
     * LSPosed isn't present so a developer running `am start` against the
     * activity still gets a working UI (the hooks won't see writes, but
     * they aren't running in that scenario).
     */
    @Suppress("DEPRECATION")
    private fun openWorldReadablePrefs(): SharedPreferences =
        try {
            getSharedPreferences(Settings.PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        }

    companion object {
        private const val MIGRATION_PREMIUM_DEFAULT_KEY = "_migration_premium_default_v1"
    }
}
