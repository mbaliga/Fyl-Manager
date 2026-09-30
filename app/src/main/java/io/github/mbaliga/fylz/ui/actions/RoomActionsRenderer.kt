package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.WheelItem
import dev.aarso.cellshell.WordWheelRail
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionId
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.ActionTarget
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.PlacementQuery
import io.github.mbaliga.fylz.actions.RoomId
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.ui.ArchiveToolsOverlay
import io.github.mbaliga.fylz.ui.BackupImportOverlay
import io.github.mbaliga.fylz.ui.BackupOverlay
import io.github.mbaliga.fylz.ui.FileHistoryOverlay

/**
 * One composable per [RoomId] (design MC.0d, §2.6): each resolves
 * `ActionResolver.resolve(PlacementQuery.Room(room), state)` and dispatches through
 * [ActionDispatcher]. Replaces `FylzV1App.kt`'s old `LibraryRail`/`LocationsRoom`/`ToolsRoom` and
 * `FylzAppShell.kt`'s five hard-coded `RecoveryActionCard`s. The surface-level gate that isn't a
 * property of any one action -- the rail drawn only when wide -- stays at the call site, as today.
 */

private val FAVOURITE_ID = ActionId.parse("fylz.favourite.toggle")

/** The left-of-content rail (design §2.6): replaces `LibraryRail`. The favourites list underneath
 * stays exactly as it was -- a display, not a command. */
@Composable
fun LibraryRailRoom(
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
    favorites: List<FavoriteLocation>,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Workspace", style = MaterialTheme.typography.titleMedium)
            resolver.resolve(PlacementQuery.Room(RoomId.LIBRARY_RAIL), state).forEach { item ->
                val icon = if (item.id == FAVOURITE_ID) {
                    BuiltinIcons.icon(if (item.checked == true) "Star" else "StarBorder")
                } else {
                    BuiltinIcons.icon(item.iconName)
                }
                val onClick = { dispatcher.run(item.id, state, null, ctx) }
                if (item.id == FAVOURITE_ID) {
                    OutlinedButton(onClick = onClick, enabled = item.enabled, modifier = Modifier.fillMaxWidth()) {
                        Icon(icon, null)
                        Text(item.label, Modifier.padding(start = 8.dp))
                    }
                } else {
                    FilledTonalButton(onClick = onClick, enabled = item.enabled, modifier = Modifier.fillMaxWidth()) {
                        Icon(icon, null)
                        Text(item.label, Modifier.padding(start = 8.dp))
                    }
                }
            }
            HorizontalDivider()
            Text("FAVOURITES", style = MaterialTheme.typography.labelSmall)
            favorites.forEach { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.weight(1f))
            Text("SAF providers supply local, cloud, USB, SMB and SFTP roots installed on the device.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The storage home surface's row in the locations wheel. Not a tab, but a real destination. */
private const val HOME_WHEEL_ID = "__home__"

/** The picker's row. A verb in a list of nouns, which is why it sits at the end. */
private const val ADD_WHEEL_ID = "__add__"

private val TAB_ADD_ID = ActionId.parse("fylz.tab.add")
private val TAB_CLOSE_ID = ActionId.parse("fylz.tab.close")

/**
 * The left room: every open location, plus the ways to get another one (design §2.6). The tab
 * list itself stays the unchanged `WordWheelRail` -- places, not commands; only its "add" and
 * "close" affordances dispatch through the registry, `fylz.tab.add` and `fylz.tab.close`, the
 * latter with the row's own tab as its target, per the design's documented convention for a
 * `requiresTarget` action placed in a room.
 */
@Composable
fun LocationsRoom(
    tabs: List<FolderTab>,
    activeTabId: String?,
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
    onSelect: (String) -> Unit,
    onOpenHome: () -> Unit,
) {
    val addItem = resolver.resolve(PlacementQuery.Room(RoomId.LOCATIONS), state).first { it.id == TAB_ADD_ID }
    val items = remember(tabs, addItem.label) {
        buildList {
            add(WheelItem(HOME_WHEEL_ID, "Home"))
            tabs.forEach { add(WheelItem(it.id, it.current.name.ifBlank { "Folder" })) }
            add(WheelItem(ADD_WHEEL_ID, addItem.label))
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding()
            .padding(start = 24.dp, end = 16.dp, top = 32.dp, bottom = 32.dp),
    ) {
        WordWheelRail(
            items = items,
            selectedId = activeTabId ?: HOME_WHEEL_ID,
            onSelect = { id ->
                when (id) {
                    HOME_WHEEL_ID -> onOpenHome()
                    ADD_WHEEL_ID -> dispatcher.run(addItem.id, state, null, ctx)
                    else -> onSelect(id)
                }
            },
            inkColor = MaterialTheme.colorScheme.onSurface,
            accentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            trailing = { item ->
                // Only the focused row gets a trailing slot, so closing is offered for the
                // location you are actually looking at -- which is also the only one where
                // "close" has an unambiguous meaning.
                tabs.firstOrNull { it.id == item.id }?.let { tab ->
                    IconButton(
                        onClick = { dispatcher.run(TAB_CLOSE_ID, state, ActionTarget.Tab(tab.id), ctx) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Close ${tab.current.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
}

private val THEME_ACTION_IDS = setOf(
    ActionId.parse("fylz.theme.system"),
    ActionId.parse("fylz.theme.light"),
    ActionId.parse("fylz.theme.dark"),
)

/**
 * The right room: the app's own tools and settings (design §2.6); replaces `ToolsRoom` and the
 * `ToolsAction` enum it dispatched through. Every row (including the theme rows, whose selection
 * marker now reads the resolved `checked`) stays text-only, exactly as `ToolsRow` always drew it.
 */
@Composable
fun ToolsRoom(resolver: ActionResolver, dispatcher: ActionDispatcher, state: BrowserState, ctx: ActionContext) {
    val items = resolver.resolve(PlacementQuery.Room(RoomId.TOOLS), state)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RoomHeading("Tools")
        items.filter { it.id !in THEME_ACTION_IDS }.forEach { item ->
            ToolsRow(item.label, enabled = item.enabled) { dispatcher.run(item.id, state, null, ctx) }
        }

        Spacer(Modifier.size(20.dp))
        RoomHeading("Appearance")
        items.filter { it.id in THEME_ACTION_IDS }.forEach { item ->
            val selected = item.checked == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { dispatcher.run(item.id, state, null, ctx) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A filled square for the chosen mode rather than a RadioButton: the same
                // marker the rail uses, so the two rooms read as one app.
                Box(Modifier.size(width = 20.dp, height = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (selected) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.6f),
                )
            }
        }
    }
}

@Composable
private fun RoomHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Moved out of `ui/FylzV1App.kt` unchanged (design MC.0d): a text-only, tappable row -- no icon,
 * exactly as the Tools room always drew it. */
@Composable
fun ToolsRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

private val HISTORY_OPERATIONS_ID = ActionId.parse("fylz.history.operations")

/** id -> the self-contained overlay composable that today's card places in its action slot
 * (design §2.6's MC.0a clarification): each owns its own FAB and dialog state, with no
 * imperative "open" the registry could call, so the renderer places the composable itself rather
 * than dispatching. `ArchiveToolsOverlay` alone needs the resolver/state/[ActionContext] to
 * render its own registry-backed menu (MC.0d's `ArchiveToolsMenuRenderer`) and, since M3.4c, to
 * hand its own "Extract" button to [ActionContext.openExtractMenu]; the other three take none. */
private val RECOVERY_OVERLAYS: Map<ActionId, @Composable (ActionResolver, BrowserState, ActionContext) -> Unit> = mapOf(
    ActionId.parse("fylz.history.files") to { _, _, _ -> FileHistoryOverlay() },
    ActionId.parse("fylz.backup.plans") to { _, _, _ -> BackupOverlay() },
    ActionId.parse("fylz.backup.import") to { _, _, _ -> BackupImportOverlay() },
    ActionId.parse("fylz.archive.tools") to { resolver, state, ctx -> ArchiveToolsOverlay(resolver, state, ctx) },
)

/**
 * The bottom room: storage, history and recovery tools (design §2.6); replaces
 * `FylzAppShell.kt`'s five hard-coded [RecoveryActionCard]s. `fylz.history.operations` is the one
 * card with a real imperative hook -- its FAB dispatches to `ctx.showOperationHistory()`, wired to
 * `FylzAppShell`'s `showHistory` since MC.0a; the other four place their overlay composable.
 */
@Composable
fun RecoveryRoom(resolver: ActionResolver, dispatcher: ActionDispatcher, state: BrowserState, ctx: ActionContext) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("Storage & recovery", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Review file operations, restore earlier file versions, manage backups, and safely work with ZIP archives.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        resolver.resolve(PlacementQuery.Room(RoomId.RECOVERY), state).forEach { item ->
            RecoveryActionCard(
                icon = BuiltinIcons.icon(item.iconName),
                title = if (item.id == HISTORY_OPERATIONS_ID) "Operation history" else item.label,
                description = recoveryDescription(item.id, state),
                action = {
                    if (item.id == HISTORY_OPERATIONS_ID) {
                        FloatingActionButton(onClick = { dispatcher.run(item.id, state, null, ctx) }) {
                            Icon(BuiltinIcons.icon(item.iconName), contentDescription = "Open operation history")
                        }
                    } else {
                        RECOVERY_OVERLAYS[item.id]?.invoke(resolver, state, ctx)
                    }
                },
            )
        }

        Text(
            "Recovery metadata stays private to this app and is excluded from Android cloud backup. External backup snapshots and archives remain in the destinations you selected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

/** Today's exact per-card description text (design §2.6); only the operations card's varies, with
 * `operationsNeedingAttention`. */
private fun recoveryDescription(id: ActionId, state: BrowserState): String = when (id.value) {
    "fylz.history.operations" -> if (state.operationsNeedingAttention == 0) {
        "Review completed, failed, cancelled, and interrupted file operations."
    } else {
        "${state.operationsNeedingAttention} operation${if (state.operationsNeedingAttention == 1) "" else "s"} need attention."
    }
    "fylz.history.files" -> "Configure local version retention, inspect saved versions, and perform verified restores."
    "fylz.backup.plans" -> "Create, schedule, run, inspect, restore, and delete transactional backups."
    "fylz.backup.import" -> "Rediscover verified manifest-bearing backup folders after reinstall or app-data loss."
    "fylz.archive.tools" -> "Create standard or AES-256 protected ZIP files, inspect archives, and extract through safety limits."
    else -> ""
}

/** Moved out of `ui/FylzAppShell.kt` unchanged (design MC.0d). */
@Composable
fun RecoveryActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    action: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}
