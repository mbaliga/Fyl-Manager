package io.github.mbaliga.fylz.actions

import android.net.Uri
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FylzClipboard
import io.github.mbaliga.fylz.model.ClipboardMode
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode

/**
 * Design §2.7 item 2, the exact fixture list `ActionResolverGoldenTest` runs every surface
 * against. Each fixture is named so a failing golden-test case says which one broke.
 */
object BrowserStateFixtures {

    private fun entry(
        name: String,
        kind: EntryKind = EntryKind.OTHER,
        uri: String = "content://fylz/$name",
    ): FileEntry = FileEntry(
        uri = Uri.parse(uri),
        name = name,
        mimeType = "application/octet-stream",
        sizeBytes = 10L,
        lastModifiedMillis = 0L,
        flags = 0,
        kind = kind,
    )

    private fun base(
        hasActiveTab: Boolean = true,
        canNavigateUp: Boolean = true,
        entries: List<FileEntry> = emptyList(),
        visibleEntries: List<FileEntry> = entries,
        selection: List<FileEntry> = emptyList(),
        selectionOrder: List<Uri> = selection.map { it.uri },
        focused: FileEntry? = null,
        clipboard: FylzClipboard? = null,
        sortSpec: SortSpec = SortSpec.Default,
        viewMode: ViewMode = ViewMode.LIST,
        previewMode: PreviewMode = PreviewMode.DOCKED,
        query: String = "",
        searchRecursive: Boolean = false,
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        currentFolderIsFavourite: Boolean = false,
        legacyBinCount: Int = 0,
        operationsNeedingAttention: Int = 0,
        locationKind: LocationKind = LocationKind.FOLDER,
        isZipFamilyArchiveLocation: Boolean = false,
    ): BrowserState = BrowserState(
        hasActiveTab = hasActiveTab,
        canNavigateUp = canNavigateUp,
        entries = entries,
        visibleEntries = visibleEntries,
        selection = selection,
        selectionOrder = selectionOrder,
        focused = focused,
        clipboard = clipboard,
        sortSpec = sortSpec,
        viewMode = viewMode,
        previewMode = previewMode,
        query = query,
        searchRecursive = searchRecursive,
        themeMode = themeMode,
        currentFolderIsFavourite = currentFolderIsFavourite,
        legacyBinCount = legacyBinCount,
        operationsNeedingAttention = operationsNeedingAttention,
        locationKind = locationKind,
        isZipFamilyArchiveLocation = isZipFamilyArchiveLocation,
    )

    private val file1 = entry("a.txt", EntryKind.TEXT)
    private val file2 = entry("b.txt", EntryKind.TEXT)
    private val dir1 = entry("Folder", EntryKind.DIRECTORY)
    private val zipArchive = entry("archive.zip", EntryKind.ARCHIVE)
    private val sevenZipArchive = entry("archive.7z", EntryKind.ARCHIVE)
    private val apkArchive = entry("app.apk", EntryKind.ARCHIVE)
    private val cbzArchive = entry("comic.cbz", EntryKind.ARCHIVE)
    private val zipNamedButNotArchiveKind = entry("not-really.zip", EntryKind.OTHER)
    private val pdf1 = entry("one.pdf", EntryKind.PDF)
    private val pdf2 = entry("two.pdf", EntryKind.PDF)
    private val pdf3 = entry("three.pdf", EntryKind.PDF)
    private val image1 = entry("photo.jpg", EntryKind.IMAGE)

    fun noTabClipboardNull(): BrowserState = base(hasActiveTab = false, canNavigateUp = false, clipboard = null)

    fun noTabClipboardSet(): BrowserState =
        base(hasActiveTab = false, canNavigateUp = false, clipboard = FylzClipboard(ClipboardMode.COPY, listOf(file1)))

    fun emptyFolder(): BrowserState = base(entries = emptyList())

    fun oneFile(): BrowserState = base(entries = listOf(file1))

    fun oneDirectory(): BrowserState = base(entries = listOf(dir1))

    fun fileAndDirectory(): BrowserState = base(entries = listOf(file1, dir1))

    fun twoFilesBoundary(): BrowserState = base(entries = listOf(file1, file2))

    fun oneZipArchiveSelected(): BrowserState = base(entries = listOf(zipArchive), selection = listOf(zipArchive))

    fun oneSevenZipArchiveSelected(): BrowserState = base(entries = listOf(sevenZipArchive), selection = listOf(sevenZipArchive))

    fun oneApkArchiveSelected(): BrowserState = base(entries = listOf(apkArchive), selection = listOf(apkArchive))

    fun oneCbzArchiveSelected(): BrowserState = base(entries = listOf(cbzArchive), selection = listOf(cbzArchive))

    fun zipNamedButNotArchiveKindSelected(): BrowserState =
        base(entries = listOf(zipNamedButNotArchiveKind), selection = listOf(zipNamedButNotArchiveKind))

    fun onePdfSelected(): BrowserState = base(entries = listOf(pdf1), selection = listOf(pdf1))

    fun threePdfsSelected(): BrowserState =
        base(entries = listOf(pdf1, pdf2, pdf3), selection = listOf(pdf1, pdf2, pdf3))

    fun pdfAndImageSelected(): BrowserState =
        base(entries = listOf(pdf1, image1), selection = listOf(pdf1, image1))

    fun focusedFileEmptySelection(): BrowserState = base(entries = listOf(file1), focused = file1, selection = emptyList())

    fun selectionContainingStaleUri(): BrowserState {
        val stale = Uri.parse("content://fylz/gone")
        return base(entries = listOf(file1), selection = listOf(file1), selectionOrder = listOf(stale, file1.uri))
    }

    fun queryFiltersEverythingOut(): BrowserState =
        base(entries = listOf(file1, file2), visibleEntries = emptyList(), query = "nomatch")

    fun recursiveSearchHitSelected(): BrowserState =
        base(entries = listOf(file1), selection = listOf(file1), query = "a", searchRecursive = true)

    fun folderDepth1(): BrowserState = base(canNavigateUp = false)

    fun folderDepth2(): BrowserState = base(canNavigateUp = true)

    fun favouriteFolder(): BrowserState = base(currentFolderIsFavourite = true)

    fun nonFavouriteFolder(): BrowserState = base(currentFolderIsFavourite = false)

    fun themeSystem(): BrowserState = base(themeMode = ThemeMode.SYSTEM)

    fun themeLight(): BrowserState = base(themeMode = ThemeMode.LIGHT)

    fun themeDark(): BrowserState = base(themeMode = ThemeMode.DARK)

    fun legacyBinCountZero(): BrowserState = base(legacyBinCount = 0)

    fun legacyBinCountOne(): BrowserState = base(legacyBinCount = 1)

    fun operationsAttention0(): BrowserState = base(operationsNeedingAttention = 0)

    fun operationsAttention1(): BrowserState = base(operationsNeedingAttention = 1)

    fun operationsAttention2(): BrowserState = base(operationsNeedingAttention = 2)

    // M3.3 (DESIGN-M33 §2.6): the three archive-location fixtures, so every read-only row is exercised.
    private val archiveEntry = entry("inside.txt", EntryKind.TEXT, uri = "content://io.github.mbaliga.fylz.archives/document/aW5zaWRl")

    fun archiveRootNoSelection(): BrowserState = base(entries = listOf(archiveEntry, dir1, pdf1), locationKind = LocationKind.ARCHIVE)

    fun archiveFolderWithSelection(): BrowserState =
        base(entries = listOf(archiveEntry, pdf1), selection = listOf(archiveEntry), focused = archiveEntry, locationKind = LocationKind.ARCHIVE)

    fun archiveWithClipboard(): BrowserState =
        base(entries = listOf(archiveEntry, pdf1), clipboard = FylzClipboard(ClipboardMode.COPY, listOf(file1)), locationKind = LocationKind.ARCHIVE)

    // M3.6 (DESIGN brief's own M3.6/M3.7): `fylz.rename`/`fylz.recycle` re-enabled for a ZIP-family
    // archive's own entries, and only those -- these two fixtures are the load-bearing case the
    // golden test exercises, side by side with the two ARCHIVE fixtures above that stay read-only.
    fun archiveZipFamilyWithSelection(): BrowserState =
        base(entries = listOf(archiveEntry, pdf1), selection = listOf(archiveEntry), locationKind = LocationKind.ARCHIVE, isZipFamilyArchiveLocation = true)

    fun archiveNonZipFamilyWithSelection(): BrowserState =
        base(entries = listOf(archiveEntry, pdf1), selection = listOf(archiveEntry), locationKind = LocationKind.ARCHIVE, isZipFamilyArchiveLocation = false)

    fun all(): List<Pair<String, BrowserState>> = listOf(
        "noTabClipboardNull" to noTabClipboardNull(),
        "noTabClipboardSet" to noTabClipboardSet(),
        "emptyFolder" to emptyFolder(),
        "oneFile" to oneFile(),
        "oneDirectory" to oneDirectory(),
        "fileAndDirectory" to fileAndDirectory(),
        "twoFilesBoundary" to twoFilesBoundary(),
        "oneZipArchiveSelected" to oneZipArchiveSelected(),
        "oneSevenZipArchiveSelected" to oneSevenZipArchiveSelected(),
        "oneApkArchiveSelected" to oneApkArchiveSelected(),
        "oneCbzArchiveSelected" to oneCbzArchiveSelected(),
        "zipNamedButNotArchiveKindSelected" to zipNamedButNotArchiveKindSelected(),
        "onePdfSelected" to onePdfSelected(),
        "threePdfsSelected" to threePdfsSelected(),
        "pdfAndImageSelected" to pdfAndImageSelected(),
        "focusedFileEmptySelection" to focusedFileEmptySelection(),
        "selectionContainingStaleUri" to selectionContainingStaleUri(),
        "queryFiltersEverythingOut" to queryFiltersEverythingOut(),
        "recursiveSearchHitSelected" to recursiveSearchHitSelected(),
        "folderDepth1" to folderDepth1(),
        "folderDepth2" to folderDepth2(),
        "favouriteFolder" to favouriteFolder(),
        "nonFavouriteFolder" to nonFavouriteFolder(),
        "themeSystem" to themeSystem(),
        "themeLight" to themeLight(),
        "themeDark" to themeDark(),
        "legacyBinCountZero" to legacyBinCountZero(),
        "legacyBinCountOne" to legacyBinCountOne(),
        "operationsAttention0" to operationsAttention0(),
        "operationsAttention1" to operationsAttention1(),
        "operationsAttention2" to operationsAttention2(),
        "archiveRootNoSelection" to archiveRootNoSelection(),
        "archiveFolderWithSelection" to archiveFolderWithSelection(),
        "archiveWithClipboard" to archiveWithClipboard(),
        "archiveZipFamilyWithSelection" to archiveZipFamilyWithSelection(),
        "archiveNonZipFamilyWithSelection" to archiveNonZipFamilyWithSelection(),
    )
}
