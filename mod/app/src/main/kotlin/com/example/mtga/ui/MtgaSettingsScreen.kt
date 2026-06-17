package com.example.mtga.ui

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch
import com.example.mtga.SettingsActivity
import com.example.mtga.common.FeatureOverride
import com.example.mtga.common.PremiumMode
import com.example.mtga.common.SettingKeys
import com.example.mtga.common.TargetSet
import com.example.mtga.common.Targets
import com.example.mtga.config.FeatureOverrideEntry
import com.example.mtga.config.PremiumModeEntry
import com.example.mtga.config.SettingItem
import com.example.mtga.config.Settings
import com.example.mtga.config.Toggle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Top-level Composable for the MTGA settings screen.
 *
 * Persistence is unchanged: every row reads its initial value from [prefs]
 * and writes through `prefs.edit().putX(...).apply()` (or `.commit()` for
 * the bottom-bar order, matching the legacy contract). This keeps the
 * cross-process LSPosed handshake intact — see
 * [com.example.mtga.config.SettingsContentProvider].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MtgaSettingsScreen(
    prefs: SharedPreferences,
    targets: TargetSet,
) {
    val visibleCategories =
        remember(targets) {
            Settings.categories
                .map { c ->
                    VisibleCategory(
                        category = c,
                        items = c.items.filter { SettingsActivity.isItemSupported(it, targets) },
                    )
                }
                .filter { it.items.isNotEmpty() }
        }
    val basic = visibleCategories.filterNot { it.category.isAdvanced }
    val advanced = visibleCategories.filter { it.category.isAdvanced }

    // Back stack of currently-open screens. Bottom of the stack is the Home
    // list; pushes happen on category tap, pops on system back / app-bar
    // back arrow. Implemented as a SnapshotStateList so the Composable
    // recomposes when navigation changes.
    val backStack = remember { mutableStateListOf<NavScreen>(NavScreen.Home) }
    val current = backStack.last()

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
    }

    MtgaTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screenTitle(current)) },
                    navigationIcon = {
                        if (backStack.size > 1) {
                            IconButton(onClick = { backStack.removeAt(backStack.lastIndex) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            val push: (NavScreen) -> Unit = { backStack.add(it) }
            when (current) {
                NavScreen.Home ->
                    HomeScreen(
                        basic = basic,
                        hasAdvanced = advanced.isNotEmpty(),
                        contentPadding = padding,
                        onCategoryClick = { push(NavScreen.Detail(it.category.title)) },
                        onAdvancedClick = { push(NavScreen.Advanced) },
                    )

                NavScreen.Advanced ->
                    AdvancedScreen(
                        advanced = advanced,
                        contentPadding = padding,
                        onCategoryClick = { push(NavScreen.Detail(it.category.title)) },
                    )

                is NavScreen.Detail -> {
                    val vc = visibleCategories.firstOrNull { it.category.title == current.categoryTitle }
                    if (vc != null) {
                        CategoryDetailScreen(category = vc, prefs = prefs, contentPadding = padding)
                    }
                }
            }
        }
    }
}

private fun screenTitle(screen: NavScreen): String =
    when (screen) {
        NavScreen.Home -> "MTGA settings"
        NavScreen.Advanced -> "Advanced"
        is NavScreen.Detail -> screen.categoryTitle
    }

/**
 * Navigation node currently displayed in [MtgaSettingsScreen]. The
 * back-stack is a `SnapshotStateList<NavScreen>` so Compose recomposes on
 * push/pop. Routes are intentionally minimal — only the data needed to
 * locate the target [VisibleCategory] travels in [Detail.categoryTitle].
 */
private sealed interface NavScreen {
    object Home : NavScreen

    object Advanced : NavScreen

    data class Detail(
        val categoryTitle: String,
    ) : NavScreen
}

/**
 * Tuple of a [com.example.mtga.config.SettingsCategory] with the subset
 * of its items that the current Truth Social build supports. Categories
 * are dropped from navigation entirely when their visible item list is
 * empty (e.g. live-carousel toggles on a v1.24.x build).
 */
private data class VisibleCategory(
    val category: com.example.mtga.config.SettingsCategory,
    val items: List<SettingItem>,
)

@Composable
private fun HomeScreen(
    basic: List<VisibleCategory>,
    hasAdvanced: Boolean,
    contentPadding: PaddingValues,
    onCategoryClick: (VisibleCategory) -> Unit,
    onAdvancedClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(basic, key = { "basic-${it.category.title}" }) { vc ->
            CategoryRow(
                title = vc.category.title,
                subtitle = categorySubtitle(vc),
                onClick = { onCategoryClick(vc) },
            )
        }
        if (hasAdvanced) {
            item("advanced-tile") {
                CategoryRow(
                    title = "Advanced",
                    subtitle = "Experimental toggles and raw Features-class flag overrides.",
                    onClick = onAdvancedClick,
                )
            }
        }
    }
}

@Composable
private fun AdvancedScreen(
    advanced: List<VisibleCategory>,
    contentPadding: PaddingValues,
    onCategoryClick: (VisibleCategory) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(advanced, key = { "advanced-${it.category.title}" }) { vc ->
            CategoryRow(
                title = vc.category.title,
                subtitle = categorySubtitle(vc),
                onClick = { onCategoryClick(vc) },
            )
        }
    }
}

@Composable
private fun CategoryDetailScreen(
    category: VisibleCategory,
    prefs: SharedPreferences,
    contentPadding: PaddingValues,
) {
    // No inline section header — the screen title in the TopAppBar already
    // names the category, so a repeated `SettingsGroup` title is noise.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(category.items, key = { itemKey(it) }) { item ->
            RenderItem(item, prefs)
        }
    }
}

private fun itemKey(item: SettingItem): String =
    when (item) {
        is SettingItem.Bool -> "bool-${item.toggle.key}"
        is SettingItem.Mode -> "mode-${item.entry.key}"
        is SettingItem.Override -> "override-${item.entry.key}"
    }

/**
 * Single-line tappable row used on the home and advanced lists. Renders
 * the category title, a one-line subtitle previewing what's inside, and
 * a trailing right-chevron so users see at a glance that the row drills
 * into another page.
 */
@Composable
private fun CategoryRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                onClick = onClick,
                role = Role.Button,
            ),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.7f),
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

/**
 * Short comma-joined preview of the labels inside a [VisibleCategory],
 * truncated to a single visual line. Used as the row subtitle on the
 * home / advanced category lists.
 */
private fun categorySubtitle(vc: VisibleCategory): String {
    val labels =
        vc.items.mapNotNull { item ->
            when (item) {
                is SettingItem.Bool -> item.toggle.label
                is SettingItem.Mode -> item.entry.label
                is SettingItem.Override -> item.entry.label
            }
        }
    return labels.joinToString(", ").take(80)
}

@Composable
private fun MtgaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dark = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            // Static M3 fallback for API < 31 (we ship to minSdk 26).
            val dark = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (dark) darkColorScheme() else lightColorScheme()
        }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@Composable
private fun RenderItem(
    item: SettingItem,
    prefs: SharedPreferences,
) {
    when (item) {
        is SettingItem.Bool -> {
            ToggleRow(item.toggle, prefs)
            // Render the reorder UI immediately under its enabling switch.
            if (item.toggle.key == SettingKeys.ReorderBottomBar) {
                BottomBarReorderBlock(prefs)
            }
        }
        is SettingItem.Mode -> PremiumModeRow(item.entry, prefs)
        is SettingItem.Override -> FeatureOverrideRow(item.entry, prefs)
    }
}

@Composable
private fun ToggleRow(
    toggle: Toggle,
    prefs: SharedPreferences,
) {
    var checked by remember {
        mutableStateOf(prefs.getBoolean(toggle.key, toggle.defaultOn))
    }
    SettingsSwitch(
        state = checked,
        title = { Text(toggle.label) },
        subtitle = { Text(toggle.description) },
        onCheckedChange = { value ->
            checked = value
            // Async write — the per-row value need not be durable before the
            // composition continues. Durability for the restart handshake is
            // provided by the synchronous RestartMarker commit in
            // SettingsActivity.onStop, not by each row.
            prefs.edit().putBoolean(toggle.key, value).apply()
        },
    )
}

/** Premium-mode tri-state row: Default / Force-enable / Hide. */
@Composable
private fun PremiumModeRow(
    entry: PremiumModeEntry,
    prefs: SharedPreferences,
) {
    var selection by remember {
        val stored = prefs.getString(entry.key, null)
        val initial = if (stored == null) entry.defaultMode else PremiumMode.fromStorage(stored)
        mutableStateOf(initial)
    }
    var pendingForceEnable by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = entry.label, style = MaterialTheme.typography.titleMedium)
        Text(
            text = entry.description,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )

        TriStateRadioRow(
            options = PremiumMode.values().toList(),
            selected = selection,
            label = { mode ->
                when (mode) {
                    PremiumMode.Default -> "Default"
                    PremiumMode.ForceEnable -> "Force enable"
                    PremiumMode.Hide -> "Hide"
                }
            },
            onSelect = { mode ->
                if (mode == selection) return@TriStateRadioRow
                if (mode == PremiumMode.ForceEnable) {
                    pendingForceEnable = true
                } else {
                    selection = mode
                    prefs.edit().putString(entry.key, mode.storageValue).apply()
                }
            },
        )
    }

    if (pendingForceEnable) {
        AlertDialog(
            onDismissRequest = { pendingForceEnable = false },
            title = { Text("Force-enable ${entry.label.lowercase()}?") },
            text = {
                Text(
                    "This bypasses the client-side Truth+ check, but the " +
                        "Truth Social server can still reject the request — " +
                        "and may flag the account for trying to use a paid " +
                        "feature without a subscription.\n\n" +
                        "Only enable this if you understand the risk.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingForceEnable = false
                    selection = PremiumMode.ForceEnable
                    prefs.edit()
                        .putString(entry.key, PremiumMode.ForceEnable.storageValue)
                        .apply()
                }) { Text("Enable anyway") }
            },
            dismissButton = {
                TextButton(onClick = { pendingForceEnable = false }) { Text("Cancel") }
            },
        )
    }
}

/** Feature override tri-state row: Default / Force ON / Force OFF. */
@Composable
private fun FeatureOverrideRow(
    entry: FeatureOverrideEntry,
    prefs: SharedPreferences,
) {
    var selection by remember {
        mutableStateOf(FeatureOverride.fromStorage(prefs.getString(entry.key, null)))
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = entry.label, style = MaterialTheme.typography.titleMedium)
        Text(
            text = entry.description,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )

        TriStateRadioRow(
            options = FeatureOverride.values().toList(),
            selected = selection,
            label = { mode ->
                when (mode) {
                    FeatureOverride.Default -> "Default"
                    FeatureOverride.ForceTrue -> "Force ON"
                    FeatureOverride.ForceFalse -> "Force OFF"
                }
            },
            onSelect = { mode ->
                if (mode == selection) return@TriStateRadioRow
                selection = mode
                prefs.edit().putString(entry.key, mode.storageValue).apply()
            },
        )
    }
}

/**
 * Horizontal three-option radio. Used by both PremiumMode and FeatureOverride
 * rows; the options list is held in a `selectableGroup` so accessibility
 * services announce the row as a single radio cluster.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> TriStateRadioRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (opt in options) {
            Box(
                modifier = Modifier
                    .selectable(
                        selected = (opt == selected),
                        onClick = { onSelect(opt) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Column(verticalArrangement = Arrangement.Center) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RadioButton(
                            selected = (opt == selected),
                            onClick = null,
                        )
                        Text(label(opt))
                    }
                }
            }
        }
    }
}

/**
 * Drag-to-reorder list for the bottom-bar tabs, plus a chip row for adding
 * routes that aren't currently in the order.
 *
 * Persists to [SettingKeys.BottomBarTabOrder] as a comma-separated string of
 * route ids; [com.example.mtga.hooks.BottomBarReorderHook] re-reads it on
 * the next host-app start.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomBarReorderBlock(prefs: SharedPreferences) {
    val knownRoutes = remember {
        Targets.latest.bottomNavTabClasses.keys.toList()
    }

    // Snapshot list backs both the reorderable LazyColumn and the chip row.
    // Initialised from prefs, falling back to the calibrated default order,
    // and pruned of any persisted-but-unknown routes (defensive against
    // pref-file drift between releases).
    val order = remember {
        val raw = prefs.getString(SettingKeys.BottomBarTabOrder, null)
            ?: SettingKeys.DefaultBottomBarTabOrder
        val parsed = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val pruned = parsed.filter { it in knownRoutes }.toMutableList()
        if (pruned.isEmpty()) {
            pruned.addAll(SettingKeys.DefaultBottomBarTabOrder.split(','))
        }
        mutableStateListOf<String>().apply { addAll(pruned) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Bottom bar order — drag rows to reorder (top = leftmost tab)",
            style = MaterialTheme.typography.bodyMedium,
        )
        // Truth Social picks Messages OR Predictions based on a server feature
        // flag, never both. Surfacing this explicitly avoids "I added it but
        // it didn't appear" confusion.
        Text(
            text = "Note: Messages (chats) and Predictions are mutually " +
                "exclusive in Truth Social — only whichever Truth Social " +
                "itself picks at runtime will actually appear.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.alpha(0.7f),
        )

        ReorderableRouteList(
            order = order,
            persist = { persistOrder(prefs, order) },
        )

        Text(
            text = "Available routes (tap to append):",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )

        val missing = knownRoutes.filter { it !in order }
        if (missing.isEmpty()) {
            Text(
                text = "(all routes included)",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (route in missing) {
                    AssistChip(
                        onClick = {
                            order.add(route)
                            persistOrder(prefs, order)
                        },
                        label = { Text("+ ${SettingsActivity.routeLabel(route)}") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReorderableRouteList(
    order: SnapshotStateList<String>,
    persist: () -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        order.add(to.index, order.removeAt(from.index))
        persist()
    }

    // The list lives inside the outer LazyColumn (the settings scroll
    // container). Constrain height so the inner LazyColumn doesn't get
    // infinite-height measurement; size to a slight overshoot of the row
    // bounding box (a 48 dp IconButton inside a 12 dp vertical padding
    // ring on each row) and disable user scroll so jitter from rounding
    // pixels doesn't show as a faint scrollable surface inside the outer
    // settings list. Drag-to-reorder still works because that uses the
    // reorderable library's own gesture handlers, not list scroll.
    val rowHeight = 68
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .height((rowHeight * order.size + 8).dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(order, key = { it }) { route ->
            ReorderableItem(reorderState, key = route) { isDragging ->
                val alpha = if (isDragging) 0.6f else 1f
                Surface(
                    modifier = Modifier.fillMaxWidth().alpha(alpha),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            modifier = Modifier.draggableHandle(),
                            onClick = {},
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Reorder",
                            )
                        }
                        Text(
                            text = "${order.indexOf(route) + 1}. ${SettingsActivity.routeLabel(route)}",
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            order.remove(route)
                            persist()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun persistOrder(
    prefs: SharedPreferences,
    order: List<String>,
) {
    prefs.edit()
        .putString(SettingKeys.BottomBarTabOrder, order.joinToString(","))
        .commit()
}
