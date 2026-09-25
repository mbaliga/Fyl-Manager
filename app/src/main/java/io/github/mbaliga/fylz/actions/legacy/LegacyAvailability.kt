package io.github.mbaliga.fylz.actions.legacy

import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.ui.isZipFamilyArchive

/**
 * Design §2.7 item 1, the differential oracle: every existing availability/enabled expression
 * from `FylzV1App.kt`, lifted word for word into a pure function. `FylzV1App.kt`'s own composables
 * now call these instead of their old inline expressions -- same values, same code path -- so the
 * shipped app runs through the same functions [io.github.mbaliga.fylz.actions.BuiltInActions] and
 * `ActionResolverGoldenTest` use. Deleted in MC.0d once nothing else calls it (design §2.7 item 1).
 */
object LegacyAvailability {
    /** `FylzV1App.kt`'s `canRename` (selection bar Rename, `:2126`). */
    fun canRename(selection: List<FileEntry>): Boolean = selection.size == 1

    /** `FylzV1App.kt`'s `canExtract` (selection bar Extract, `:2129`). */
    fun canExtract(selection: List<FileEntry>): Boolean =
        selection.size == 1 && selection.first().kind == EntryKind.ARCHIVE && isZipFamilyArchive(selection.first().name)

    /** `FylzV1App.kt`'s `canPdfTools` (selection bar PDF tools, `:2131`). */
    fun canPdfTools(selection: List<FileEntry>): Boolean =
        selection.isNotEmpty() && selection.all { it.kind == EntryKind.PDF }

    /** Overflow menu's "New folder" `enabled =` (`:1076`). */
    fun newFolderEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** Overflow menu's "New text file" `enabled =` (`:1082`). */
    fun newFileEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** Overflow menu's "Scan to PDF" `enabled =` (`:1088`). */
    fun scanToPdfEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** Overflow menu's "Find duplicates" `enabled =` (`:1099`). */
    fun findDuplicatesEnabled(entries: List<FileEntry>): Boolean = entries.count { !it.isDirectory } > 1

    /** Overflow menu's "AI organize proposal" `enabled =` (`:1121`). */
    fun aiOrganizeEnabled(focused: FileEntry?): Boolean = focused != null

    /** The clipboard chip's own `enabled =` (`:1041`). */
    fun clipboardChipEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** Navigate-up's `enabled =` (`:1736`): `activeTab.locations.size > 1`. */
    fun canNavigateUp(locations: List<FolderLocation>?): Boolean = (locations?.size ?: 0) > 1

    /** Select-all's `enabled =` (`:1752`): `entries.isNotEmpty()` over the browser's visible entries. */
    fun selectAllEnabled(visibleEntries: List<FileEntry>): Boolean = visibleEntries.isNotEmpty()

    /** The rail's Favourite `enabled =` (`:1667`). */
    fun favouriteEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab
}
