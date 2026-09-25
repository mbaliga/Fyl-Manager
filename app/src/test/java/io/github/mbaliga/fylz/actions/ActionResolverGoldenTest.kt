package io.github.mbaliga.fylz.actions

import io.github.mbaliga.fylz.actions.legacy.LegacyAvailability
import io.github.mbaliga.fylz.browse.SortField
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
 * `expected*` function re-derives it from [LegacyAvailability] and the raw [BrowserState] fields,
 * transcribed from `FylzV1App.kt`'s/`FylzAppShell.kt`'s own code, so a bug in the registry's
 * wiring (wrong order, wrong placement, a dropped item) is caught even though the *predicates*
 * are the same functions production now runs through.
 */
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
        return listOf(
            Triple(id("fylz.cut"), true, null),
            Triple(id("fylz.copy"), true, null),
            Triple(id("fylz.copy-to"), true, null),
            Triple(id("fylz.move-to"), true, null),
            Triple(id("fylz.recycle"), true, null),
            Triple(id("fylz.rename"), LegacyAvailability.canRename(state.selection), null),
            Triple(id("fylz.tags"), true, null),
            Triple(id("fylz.compress"), true, null),
            Triple(id("fylz.extract"), LegacyAvailability.canExtract(state.selection), null),
            Triple(id("fylz.rename.batch"), true, null),
            Triple(id("fylz.pdf.tools"), LegacyAvailability.canPdfTools(state.selection), null),
            Triple(id("fylz.share"), true, null),
            Triple(id("fylz.select.clear"), true, null),
        )
    }

    private fun expectedTopAppBar(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> {
        val result = mutableListOf<Triple<ActionId, Boolean, Boolean?>>()
        if (state.clipboard != null) {
            result += Triple(id("fylz.paste"), LegacyAvailability.clipboardChipEnabled(state.hasActiveTab), null)
            result += Triple(id("fylz.clipboard.clear"), true, null)
        }
        result += Triple(id("fylz.view.toggle"), true, null)
        result += Triple(id("fylz.refresh"), true, null)
        return result
    }

    private fun expectedOverflow(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.new-folder"), LegacyAvailability.newFolderEnabled(state.hasActiveTab), null),
        Triple(id("fylz.new-file"), LegacyAvailability.newFileEnabled(state.hasActiveTab), null),
        Triple(id("fylz.scan-to-pdf"), LegacyAvailability.scanToPdfEnabled(state.hasActiveTab), null),
        Triple(id("fylz.find-duplicates"), LegacyAvailability.findDuplicatesEnabled(state.entries), null),
        Triple(id("fylz.ai.organize"), LegacyAvailability.aiOrganizeEnabled(state.focused), null),
        Triple(id("fylz.commands"), true, null),
    )

    private fun expectedBrowserRow(state: BrowserState): List<Triple<ActionId, Boolean, Boolean?>> = listOf(
        Triple(id("fylz.navigate.up"), state.canNavigateUp, null),
        Triple(id("fylz.select.all"), LegacyAvailability.selectAllEnabled(state.visibleEntries), null),
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
        Triple(id("fylz.favourite.toggle"), LegacyAvailability.favouriteEnabled(state.hasActiveTab), state.currentFolderIsFavourite),
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
        }
    }
}
