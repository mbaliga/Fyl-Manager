package io.github.mbaliga.fylz.actions

import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.model.BrowsableArchiveFormats
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Design §2.7, the differential oracle: for every fixture and every surface,
 * `ActionResolver.resolve(surface, state)` must equal what the legacy availability functions plus
 * the code's literal item order say -- `(id, enabled)` pairs, and `checked` where the built-in
 * table gives one. Nothing here calls [BuiltInActions] to compute the expected side; each
 * `expected*` function re-derives it from [LegacyOracle] and the raw [BrowserState] fields,
 * transcribed from `FylzV1App.kt`'s/`FylzAppShell.kt`'s own code, so a bug in the registry's
 * wiring (wrong order, wrong placement, a dropped item) is caught even though the *predicates*
 * are the same functions production now runs through.
 *
 * `LegacyOracle` is this test's own frozen copy of the pre-refactor expressions (MC.0f, design
 * §2.7 item 1): `actions/legacy/LegacyAvailability.kt`, which production called through MC.0a-e,
 * is deleted once the built-in table inlines the same expressions (MC.0f), so the differential
 * check keeps its independent oracle by holding a private copy here instead of calling production
 * code.
 */
private object LegacyOracle {
    /** `FylzV1App.kt:1133` at `6e3ab3f`. */
    fun canRename(selection: List<FileEntry>): Boolean = selection.size == 1

    /** M3.4c (design §2.1): `selection.size == 1 && BrowsableArchiveFormats.matches(name)` -- no
     * `EntryKind` precondition any more, a deliberate widening from the pre-M3.4 zip4j-only rule
     * (`FylzV1App.kt:1134-1136` at `6e3ab3f`) now that every browsable format extracts through the
     * queue, not only the ones classified `EntryKind.ARCHIVE`. */
    fun canExtract(selection: List<FileEntry>): Boolean =
        selection.size == 1 && BrowsableArchiveFormats.matches(selection.first().name)

    /** `fylz.extract.selected` (design §2.1): any non-empty selection inside a browsed archive. */
    fun canExtractSelected(selection: List<FileEntry>): Boolean = selection.isNotEmpty()

    /** `FylzV1App.kt:1137-1138` at `6e3ab3f`. */
    fun canPdfTools(selection: List<FileEntry>): Boolean =
        selection.isNotEmpty() && selection.all { it.kind == EntryKind.PDF }

    /** `FylzV1App.kt:1041` at `6e3ab3f`. */
    fun clipboardChipEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** `FylzV1App.kt:1076` at `6e3ab3f`. */
    fun newFolderEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** `FylzV1App.kt:1082` at `6e3ab3f`. */
    fun newFileEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** `FylzV1App.kt:1088` at `6e3ab3f`. */
    fun scanToPdfEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** `FylzV1App.kt:1099` at `6e3ab3f`. */
    fun findDuplicatesEnabled(entries: List<FileEntry>): Boolean = entries.count { !it.isDirectory } > 1

    /** `FylzV1App.kt:1121` at `6e3ab3f`. */
    fun aiOrganizeEnabled(focused: FileEntry?): Boolean = focused != null

    /** `FylzV1App.kt:1752` at `6e3ab3f`: `entries.isNotEmpty()` over the browser's visible entries. */
    fun selectAllEnabled(visibleEntries: List<FileEntry>): Boolean = visibleEntries.isNotEmpty()

    /** `FylzV1App.kt:1667` at `6e3ab3f`. */
    fun favouriteEnabled(hasActiveTab: Boolean): Boolean = hasActiveTab

    /** M3.3 (DESIGN-M33 §2.6): inside an archive location every action that writes the selection's
     * source or the current location is disabled -- read-only until M3.6. The frozen twin of
     * `BuiltInActions`' `WRITABLE_LOCATION`. */
    fun writableLocation(state: BrowserState): Boolean = state.locationKind != LocationKind.ARCHIVE
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionResolverGoldenTest {

    private val registry = ActionRegistry(BuiltInActions.all())
    private val resolver = ActionResolver(registry)

    private fun id(value: String) = ActionId.parse(value)

    private fun resolved(query: PlacementQuery, state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> =
        resolver.resolve(query, state).map { Triple(it.id, it.enabled, it.checked) }

    private fun expectedSelectionBar(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> {
        if (state.selectionCount == 0) return emptyList()
        val writable = LegacyOracle.writableLocation(state)
        // M3.4c (design §2.1): exactly one slot-90 action renders, by locationKind.
        val extractSlot = if (state.locationKind == LocationKind.ARCHIVE) {
            Triple(id("fylz.extract.selected"), LegacyOracle.canExtractSelected(state.selection), null)
        } else {
            Triple(id("fylz.extract"), LegacyOracle.canExtract(state.selection), null)
        }
        return listOf(
            Triple(id("fylz.cut"), writable, null),
            Triple(id("fylz.copy"), true, null),
            Triple(id("fylz.copy-to"), true, null),
            Triple(id("fylz.move-to"), writable, null),
            Triple(id("fylz.recycle"), writable, null),
            Triple(id("fylz.rename"), LegacyOracle.canRename(state.selection) && writable, null),
            Triple(id("fylz.tags"), writable, null),
            Triple(id("fylz.compress"), true, null),
            extractSlot,
            Triple(id("fylz.rename.batch"), writable, null),
            Triple(id("fylz.pdf.tools"), LegacyOracle.canPdfTools(state.selection), null),
            Triple(id("fylz.share"), true, null),
            Triple(id("fylz.select.clear"), true, null),
        )
    }

    /** `Menu(MenuId.EXTRACT)`, the Extract sheet's own three choices (design §2.1): as visible
     * and enabled as `fylz.extract` itself, so never shown inside an archive location. */
    private fun expectedExtractMenu(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> {
        if (state.selectionCount == 0 || state.locationKind == LocationKind.ARCHIVE) return emptyList()
        val enabled = LegacyOracle.canExtract(state.selection)
        return listOf(
            Triple(id("fylz.extract.here"), enabled, null),
            Triple(id("fylz.extract.folder"), enabled, null),
            Triple(id("fylz.extract.to"), enabled, null),
        )
    }

    private fun expectedTopAppBar(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> {
        val result = mutableListOf<Triple<ActionId, Boolean, Boolean?>>()
        if (state.clipboard != null) {
            result += Triple(id("fylz.paste"), LegacyOracle.clipboardChipEnabled(state.hasActiveTab) && LegacyOracle.writableLocation(state), null)
            result += Triple(id("fylz.clipboard.clear"), true, null)
        }
        result += Triple(id("fylz.view.toggle"), true, null)
        result += Triple(id("fylz.refresh"), true, null)
        return result
    }

    private fun expectedOverflow(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> {
        val writable = LegacyOracle.writableLocation(state)
        return listOf(
            Triple(id("fylz.new-folder"), LegacyOracle.newFolderEnabled(state.hasActiveTab) && writable, null),
            Triple(id("fylz.new-file"), LegacyOracle.newFileEnabled(state.hasActiveTab) && writable, null),
            Triple(id("fylz.scan-to-pdf"), LegacyOracle.scanToPdfEnabled(state.hasActiveTab) && writable, null),
            Triple(id("fylz.find-duplicates"), LegacyOracle.findDuplicatesEnabled(state.entries) && writable, null),
            Triple(id("fylz.ai.organize"), LegacyOracle.aiOrganizeEnabled(state.focused) && writable, null),
            Triple(id("fylz.commands"), true, null),
        )
    }

    private fun expectedBrowserRow(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.navigate.up"), state.canNavigateUp, null),
        Triple(id("fylz.select.all"), LegacyOracle.selectAllEnabled(state.visibleEntries), null),
    )

    private fun expectedSort(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.sort.name"), true, state.sortSpec.field == SortField.NAME),
        Triple(id("fylz.sort.size"), true, state.sortSpec.field == SortField.SIZE),
        Triple(id("fylz.sort.modified"), true, state.sortSpec.field == SortField.MODIFIED),
        Triple(id("fylz.sort.type"), true, state.sortSpec.field == SortField.TYPE),
        Triple(id("fylz.sort.folders-first"), true, state.sortSpec.foldersFirst),
    )

    private fun expectedLocations(): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.tab.add"), true, null),
        Triple(id("fylz.tab.close"), true, null),
    )

    private fun expectedLibraryRail(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.open-root"), true, null),
        Triple(id("fylz.favourite.toggle"), LegacyOracle.favouriteEnabled(state.hasActiveTab) && LegacyOracle.writableLocation(state), state.currentFolderIsFavourite),
        Triple(id("fylz.recycle-bin"), true, null),
    )

    private fun expectedTools(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.recycle-bin"), true, null),
        Triple(id("fylz.remotes"), true, null),
        Triple(id("fylz.webdav.quick"), true, null),
        Triple(id("fylz.tools"), true, null),
        Triple(id("fylz.index"), true, null),
        Triple(id("fylz.theme.system"), true, state.themeMode == ThemeMode.SYSTEM),
        Triple(id("fylz.theme.light"), true, state.themeMode == ThemeMode.LIGHT),
        Triple(id("fylz.theme.dark"), true, state.themeMode == ThemeMode.DARK),
        // fylz.customisation.problems: visible only when registry.problems is non-empty, which it
        // never is for the shipped built-ins (design deviation (c); ShortcutTableTest asserts this).
    )

    private fun expectedRecovery(): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.history.operations"), true, null),
        Triple(id("fylz.history.files"), true, null),
        Triple(id("fylz.backup.plans"), true, null),
        Triple(id("fylz.backup.import"), true, null),
        Triple(id("fylz.archive.tools"), true, null),
    )

    private fun expectedArchiveTools(): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.protect"), true, null),
        Triple(id("fylz.archive.inspect"), true, null),
    )

    @Test
    fun `every surface matches the legacy oracle for every fixture`() {
        for ((name, state) in BrowserStateFixtures.all()) {
            assertEquals("selectionBar/$name", expectedSelectionBar(state), resolved(PlacementQuery.SelectionBar, state))
            assertEquals(
                "topAppBar/$name",
                expectedTopAppBar(state),
                resolved(PlacementQuery.Toolbar(Bar.TOP_APP_BAR), state),
            )
            assertEquals("overflow/$name", expectedOverflow(state), resolved(PlacementQuery.Menu(MenuId.OVERFLOW), state))
            assertEquals(
                "browserRow/$name",
                expectedBrowserRow(state),
                resolved(PlacementQuery.Toolbar(Bar.BROWSER_ROW), state),
            )
            assertEquals("sort/$name", expectedSort(state), resolved(PlacementQuery.Menu(MenuId.SORT), state))
            assertEquals(
                "locations/$name",
                expectedLocations(),
                resolved(PlacementQuery.Room(RoomId.LOCATIONS), state),
            )
            assertEquals(
                "libraryRail/$name",
                expectedLibraryRail(state),
                resolved(PlacementQuery.Room(RoomId.LIBRARY_RAIL), state),
            )
            assertEquals("tools/$name", expectedTools(state), resolved(PlacementQuery.Room(RoomId.TOOLS), state))
            assertEquals(
                "recovery/$name",
                expectedRecovery(),
                resolved(PlacementQuery.Room(RoomId.RECOVERY), state),
            )
            assertEquals(
                "archiveTools/$name",
                expectedArchiveTools(),
                resolved(PlacementQuery.Menu(MenuId.ARCHIVE_TOOLS), state),
            )
            assertEquals(
                "extractMenu/$name",
                expectedExtractMenu(state),
                resolved(PlacementQuery.Menu(MenuId.EXTRACT), state),
            )
        }
    }
}
