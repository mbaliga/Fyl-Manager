package io.github.mbaliga.fylz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.operations.SelectionActions

/** Everything the bottom room can ask the workspace to do. */
internal enum class FylzAction {
    COPY,
    MOVE,
    RECYCLE,
    RENAME,
    BATCH_RENAME,
    TAGS,
    ARCHIVE,
    EXTRACT,
    PDF_TOOLS,
    ANNOTATE,
    SHARE,
    ADD_TO_SHELF,
    PIN_TO_DESKTOP,
    CLEAR_SELECTION,
    NEW_FOLDER,
    NEW_FILE,
    SCAN_PDF,
    FIND_DUPLICATES,
    AI_ORGANIZE,
}

/**
 * The bottom room: everything that changes a file.
 *
 * It replaces two surfaces that were never one thing. The first was a contextual bottom bar with
 * eleven buttons in a horizontal scroller — a control you had to swipe sideways to read, whose
 * right-hand half most people never saw, and which covered the listing it acted on. The second
 * was the top bar's overflow menu, which held "New folder" and "Scan to PDF" next to "AI organize
 * proposal" because there was nowhere else to put them. Both were chrome renting space from the
 * file list; both are now card carousels on a surface that is only there when asked for.
 *
 * Pairs with [DetailsRoom] above: **up is what you are looking at, down is what to do about it.**
 *
 * ### Actions appear only when they apply
 *
 * Inapplicable actions are absent, not greyed out. Each group is read left to right, so a shorter
 * row is a faster one, and a disabled card is a question the user has to answer ("why can't I?")
 * for no benefit. [SelectionActions] decides — this file contains no rules, only cards, which is
 * what makes the rules testable without a device.
 *
 * Recovery keeps this edge. It was the bottom room before actions arrived, it is reached by the
 * same drag, and it belongs here on the merits: finishing an interrupted move is an action on
 * files, not a setting. It sits last because it is the least frequent.
 *
 * @param selection what the current selection supports, from `SelectionActionPolicy`.
 * @param folderOpen whether a location is open — with none, only recovery has anything to offer.
 * @param canFindDuplicates whether the folder holds enough files for a duplicate scan to mean
 *   anything.
 * @param canOrganize whether an entry is focused for the AI organiser to propose a home for.
 * @param recovery the storage-and-recovery section, owned and composed by the app shell.
 */
@Composable
internal fun ActionsRoom(
    selection: SelectionActions,
    folderOpen: Boolean,
    canFindDuplicates: Boolean,
    canOrganize: Boolean,
    onAction: (FylzAction) -> Unit,
    recovery: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        if (selection.any) {
            RoomHeading(
                if (selection.count == 1) "Selection · 1 item" else "Selection · ${selection.count} items",
            )
            ActionCardRow {
                if (selection.copy) {
                    item {
                        ActionCard(Icons.Outlined.ContentCopy, "Copy to…", "Duplicate into another folder") {
                            onAction(FylzAction.COPY)
                        }
                    }
                }
                if (selection.move) {
                    item {
                        ActionCard(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to…", "Relocate into another folder") {
                            onAction(FylzAction.MOVE)
                        }
                    }
                }
                if (selection.rename) {
                    item {
                        ActionCard(Icons.Outlined.Edit, "Rename", "Give it a new name") {
                            onAction(FylzAction.RENAME)
                        }
                    }
                }
                if (selection.batchRename) {
                    item {
                        ActionCard(Icons.AutoMirrored.Outlined.TextSnippet, "Batch rename", "Rename many files at once") {
                            onAction(FylzAction.BATCH_RENAME)
                        }
                    }
                }
                if (selection.tag) {
                    item {
                        ActionCard(Icons.Outlined.Tag, "Tags", "Label for quick search later") {
                            onAction(FylzAction.TAGS)
                        }
                    }
                }
                if (selection.archive) {
                    item {
                        ActionCard(Icons.Outlined.Archive, "Add to a ZIP…", "Compress into an archive") {
                            onAction(FylzAction.ARCHIVE)
                        }
                    }
                }
                if (selection.extract) {
                    item {
                        ActionCard(Icons.Outlined.FolderOpen, "Extract to…", "Unpack into a folder") {
                            onAction(FylzAction.EXTRACT)
                        }
                    }
                }
                if (selection.pdfTools) {
                    item {
                        ActionCard(Icons.Outlined.PictureAsPdf, "PDF tools", "Merge, split, or convert") {
                            onAction(FylzAction.PDF_TOOLS)
                        }
                    }
                }
                if (selection.annotate) {
                    item {
                        ActionCard(Icons.Outlined.Draw, "Annotate", "Draw on the photo") {
                            onAction(FylzAction.ANNOTATE)
                        }
                    }
                }
                if (selection.share) {
                    item {
                        ActionCard(Icons.Outlined.Share, "Share", "Send outside Fylz") {
                            onAction(FylzAction.SHARE)
                        }
                    }
                }
                // Any non-empty selection can go to the Shelf, staged there for later.
                item {
                    ActionCard(Icons.Outlined.Inventory2, "Add to Shelf", "Stage for actions across folders") {
                        onAction(FylzAction.ADD_TO_SHELF)
                    }
                }
                // Any non-empty selection can be pinned to the desktop, same as the Shelf above.
                item {
                    ActionCard(Icons.Outlined.PushPin, "Pin to desktop", "Add a shortcut to the desktop") {
                        onAction(FylzAction.PIN_TO_DESKTOP)
                    }
                }
                // Destructive last: the one card in the row worth a second glance before tapping.
                if (selection.recycle) {
                    item {
                        ActionCard(
                            Icons.Outlined.Delete,
                            "Move to Recycle Bin",
                            "Delete, recoverable later",
                            destructive = true,
                        ) { onAction(FylzAction.RECYCLE) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (folderOpen) {
            RoomHeading("This folder")
            ActionCardRow {
                item {
                    ActionCard(Icons.Outlined.CreateNewFolder, "New folder", "Start an empty folder here") {
                        onAction(FylzAction.NEW_FOLDER)
                    }
                }
                item {
                    ActionCard(Icons.AutoMirrored.Outlined.TextSnippet, "New text file", "Start an empty text file") {
                        onAction(FylzAction.NEW_FILE)
                    }
                }
                item {
                    ActionCard(Icons.Outlined.PictureAsPdf, "Scan to PDF", "Capture pages with the camera") {
                        onAction(FylzAction.SCAN_PDF)
                    }
                }
                if (canFindDuplicates) {
                    item {
                        ActionCard(Icons.Outlined.ContentCopy, "Find duplicates", "Scan this folder for copies") {
                            onAction(FylzAction.FIND_DUPLICATES)
                        }
                    }
                }
                if (canOrganize) {
                    item {
                        ActionCard(Icons.Outlined.AutoAwesome, "AI organize proposal", "Suggest a better home for this file") {
                            onAction(FylzAction.AI_ORGANIZE)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        RoomHeading("Storage & recovery")
        recovery()
    }
}

/**
 * A horizontal carousel of [ActionCard]s, shared by every group in this room and by the recovery
 * section in `FylzAppShell`. The trailing content padding is narrower than a card, so a row that
 * overflows the screen always cuts through its last visible card instead of stopping on a clean
 * edge — that sliver is the only hint a room this quiet needs that there is more to scroll to.
 */
@Composable
internal fun ActionCardRow(content: LazyListScope.() -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(ACTION_CARD_SPACING),
        contentPadding = PaddingValues(end = ACTION_CARD_PEEK),
        content = content,
    )
}

/**
 * One action, or one recovery surface: an icon, a title, and a subtitle that says what tapping it
 * does. Icon plus words, never icon alone — `docs/DESIGN.md` requires a semantic label on every
 * icon-only control, and the cheapest way to satisfy that is to not build icon-only controls. The
 * glyph is decorative here — the title and subtitle beside it are what a screen reader reads — so
 * it carries no content description of its own rather than repeating the label.
 */
@Composable
internal fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // A FIXED height, not just a width: the title already wraps to two lines and the subtitle
        // now may too, so cards left to size themselves end up different heights and the carousel
        // reads ragged along its bottom edge (the owner's recording shows exactly that, with
        // "Operation history" standing a line taller than its neighbours). Sized to the tallest
        // legal card -- icon 24 + 12 + two title lines + 2 + two subtitle lines + 16dp padding
        // top and bottom -- so every card in every row matches whatever its own copy needs.
        modifier = Modifier.width(ACTION_CARD_WIDTH).height(ACTION_CARD_HEIGHT),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Two lines, not one. A 150dp card minus 16dp of padding each side leaves 118dp of
                // text, and at labelSmall essentially EVERY subtitle in this room overflows that:
                // "Start an empty folder here", "Capture pages with the camera", "Review past file
                // operations" all cut mid-word at one line, which is what made the whole room read
                // as broken rather than merely tight.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

internal val ACTION_CARD_WIDTH = 150.dp
/** Every card in every [ActionCardRow] stands exactly this tall -- see [ActionCard]'s own note. */
private val ACTION_CARD_HEIGHT = 148.dp
private val ACTION_CARD_SPACING = 12.dp
private val ACTION_CARD_PEEK = 32.dp
