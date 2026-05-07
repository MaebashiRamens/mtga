package com.example.mtga

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.mtga.common.PremiumMode
import com.example.mtga.common.SettingKeys
import com.example.mtga.config.PremiumModeEntry
import com.example.mtga.config.SettingItem
import com.example.mtga.config.Settings
import com.example.mtga.config.SettingsCategory
import com.example.mtga.config.Toggle

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Discard any saved RadioButton/Switch state from a previous activity
        // instance — we re-initialize from the persisted prefs file instead,
        // and stale view-state restoration was causing the radios to render
        // with the wrong selection.
        super.onCreate(null)
        title = "MTGA settings"
        setContentView(buildContentView())
    }

    private fun buildContentView(): ScrollView {
        val prefs = openWorldReadablePrefs()
        runPremiumDefaultMigration(prefs)
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

        column.addView(
            header(
                "MTGA — Truth Social Mod",
                "Toggles take effect after the host app restarts.\n" +
                    "Read-only access from Truth Social process is via WorldReadable shared prefs.",
            ),
        )

        Settings.categories.forEachIndexed { index, category ->
            if (index > 0) column.addView(divider())
            column.addView(sectionTitle(category.title))
            for (item in category.items) {
                when (item) {
                    is SettingItem.Bool -> column.addView(toggleRow(item.toggle, prefs))
                    is SettingItem.Mode -> column.addView(premiumModeRow(item.entry, prefs))
                }
            }
        }

        return ScrollView(this).apply {
            addView(column, ViewParams(MATCH_PARENT, MATCH_PARENT))
        }
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
     * Mutually exclusive — selecting one clears the others automatically.
     *
     * Force-enable lies to the client about Truth+ status; the server can
     * still reject and using these features risks the account. We surface a
     * confirmation dialog before persisting that choice and revert the radio
     * if the user backs out.
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
        // button's onChange + the new checked button's onChange both reach the
        // group), so we both suppress the next callback after revertTo and
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
     * One-shot migration: clear any premium-mode values left in the prefs
     * file so the entry's defaultMode (Hide) takes effect, and so any
     * Force-enable choice now goes through the risk-warning dialog. Runs
     * once per install, gated by [MIGRATION_PREMIUM_DEFAULT_KEY].
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
     * MODE_WORLD_READABLE on stock Android throws SecurityException since API
     * 24. LSPosed v1.x+ hooks the StrictMode check away for Xposed modules, so
     * the call succeeds on a rooted+LSPosed install and the write lands in
     * LSPosed's managed prefs dir where [XSharedPreferences] in the hooked
     * process can read it. We fall back to MODE_PRIVATE if LSPosed isn't
     * present so a developer running `am start` against the activity directly
     * still gets a working UI (the hooks won't see the writes, but neither
     * are they running in that scenario).
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
