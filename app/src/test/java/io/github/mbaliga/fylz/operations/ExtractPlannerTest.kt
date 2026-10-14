package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveFormatFamily
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.VolumeInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * `ExtractPlanner` (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.2) over the hosted file
 * provider and [FakeArchiveDecoder]: expansion to a bitmap with links counted and hardlink targets
 * pulled in, the three layouts, the structural gate read fail-closed from the summary, encryption
 * routing, the large-`Here` prompt, vfat's per-entry rule, FAT sanitisation and uniquification,
 * conflicts from one listing, and consent -- through the headless mode and a scripted UI.
 */
class ExtractPlannerTest : FylzDocumentsProviderTestBase() {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var catalog: ArchiveCatalog
    private lateinit var destinationDir: File
    private lateinit var destination: Uri
    private var volume: VolumeInfo? = ExtractTestArchives.ext4()

    @Before
    fun setUp() {
        stub = FakeArchiveDecoder()
        catalog = ExtractTestArchives.catalog(context, stub)
        destinationDir = File(rootDir, "dest").apply { mkdirs() }
        destination = ExtractTestArchives.treeUri("dest")
    }

    private fun planner(threshold: Int = ExtractPlanner.HERE_ROOT_THRESHOLD) = ExtractPlanner(context.contentResolver, catalog, volumeFor = { volume }, hereRootThreshold = threshold)

    private fun archive(name: String = "a.zip", fake: FakeArchive = ExtractTestArchives.sample()): ArchiveRef = ExtractTestArchives.write(rootDir, name, fake)

    private fun request(ref: ArchiveRef, selection: ExtractSelection = ExtractSelection.All, layout: ExtractLayoutRequest = ExtractLayoutRequest.Here) =
        ExtractRequest(ref, selection, destination, layout)

    private fun plan(request: ExtractRequest, ui: PlannerUi = HeadlessPlannerUi()): ExtractPlanResult = runBlocking { planner().plan(request, ui) }

    private fun planned(request: ExtractRequest, ui: PlannerUi = HeadlessPlannerUi()): ExtractPlanResult.Planned {
        val result = plan(request, ui)
        check(result is ExtractPlanResult.Planned) { "expected Planned, got $result" }
        return result
    }

    private fun entryId(ref: ArchiveRef, ordinal: Int, path: String) = ArchiveDocumentId(ref.source, ref.chain, ordinal, path)

    @Test
    fun `Here expands the whole archive to its ordinals, one item per root, links counted, the hardlink target pulled in`() {
        val ref = archive()
        val result = planned(request(ref))
        val (operation, plan, summary) = result
        assertEquals(FileOperationType.EXTRACT, operation.type)
        assertEquals(OperationState.QUEUED, operation.state)
        assertEquals(destination, operation.destination)
        assertEquals(ConflictPolicy.SKIP, operation.conflictPolicy)
        assertEquals(listOf("docs", "hello.txt", "images", "late", "bad?name.txt"), operation.items.map { it.displayName })
        assertTrue(operation.items.all { it.destination == destination && it.state == OperationState.QUEUED })
        assertEquals(ExtractLayout.HERE, plan.layout)
        assertNull(plan.folderName)
        assertEquals(ArchiveDocumentId.root(ref).toUri(), plan.archiveUri)
        assertEquals(operation.id, plan.operationId)
        // Files, explicit directories and the hardlink's target; never the symlink or the link itself.
        assertEquals(listOf(0, 1, 2, 3, 4, 7, 8, 9), plan.ordinals.ordinals().toList())
        assertEquals(1, summary.skippedLinks)
        assertEquals(8, summary.entryCount)
        val total = ExtractTestArchives.expectedFiles.values.sumOf { it.size.toLong() }
        assertEquals(total, summary.totalBytes)
        assertFalse(summary.needsConsent)
        assertFalse(plan.consent)
        assertFalse(plan.sanitize)
        assertEquals(ArchiveLimits.forExtraction(volume, consent = false), plan.limits)
        // Items: root paths, names, expected bytes (docs holds the hardlink's copy of hello).
        assertEquals(listOf("docs", "hello.txt", "images", "late", "bad?name.txt"), plan.items.map { it.rootPath })
        assertEquals((0..4).toList(), plan.items.map { it.itemIndex })
        assertEquals(ExtractTestArchives.readme.size + ExtractTestArchives.guide.size + ExtractTestArchives.hello.size.toLong(), operation.items[0].expectedBytes)
        assertEquals(ExtractTestArchives.hello.size.toLong(), operation.items[1].expectedBytes)
        assertTrue(plan.items.all { it.conflictPolicy == ConflictPolicy.SKIP && it.nameOverride == null })
        // Item sources are the entries' archive documents.
        assertEquals(entryId(ref, ExtractTestArchives.DOCS, "docs"), ArchiveDocumentId.parse(operation.items[0].source))
        assertEquals(entryId(ref, ArchiveDocumentId.IMPLICIT_ORDINAL, "images"), ArchiveDocumentId.parse(operation.items[2].source))
        assertEquals(1, stub.listCalls.get())
    }

    @Test
    fun `IntoFolder is one item holding everything, named as asked`() {
        val ref = archive()
        val (operation, plan, _) = planned(request(ref, layout = ExtractLayoutRequest.IntoFolder("photos")))
        assertEquals(ExtractLayout.INTO_FOLDER, plan.layout)
        assertEquals("photos", plan.folderName)
        assertEquals(listOf("photos"), operation.items.map { it.displayName })
        assertEquals(listOf(""), plan.items.map { it.rootPath })
        assertEquals(ArchiveDocumentId.root(ref), ArchiveDocumentId.parse(operation.items.single().source))
        assertEquals(listOf(0, 1, 2, 3, 4, 7, 8, 9), plan.ordinals.ordinals().toList())
        assertEquals(ExtractTestArchives.expectedFiles.values.sumOf { it.size.toLong() }, operation.items.single().expectedBytes)
    }

    @Test
    fun `Entries selects files and folders, drops a selected descendant of a selected folder, and reads an unselected hardlink target`() {
        val ref = archive()
        val ids = listOf(
            entryId(ref, ExtractTestArchives.DOCS, "docs"),
            entryId(ref, ExtractTestArchives.GUIDE, "docs/guide.md"),
            entryId(ref, ExtractTestArchives.LATE_X, "late/x.txt"),
        )
        val (operation, plan, summary) = planned(request(ref, ExtractSelection.Entries(ids)))
        assertEquals(ExtractLayout.ENTRIES, plan.layout)
        assertEquals(listOf("docs", "x.txt"), operation.items.map { it.displayName })
        assertEquals(listOf("docs", "late/x.txt"), plan.items.map { it.rootPath })
        // docs' files, docs itself, the hardlink's target (hello.txt, unselected) and x.txt.
        assertEquals(listOf(0, 1, 2, 3, 7), plan.ordinals.ordinals().toList())
        assertEquals(1, summary.skippedLinks)
        assertEquals(2, summary.itemCount)
    }

    @Test
    fun `a stale or foreign selection is refused`() {
        val ref = archive()
        assertTrue(plan(request(ref, ExtractSelection.Entries(listOf(entryId(ref, 42, "docs/guide.md"))))) is ExtractPlanResult.Refused)
        assertTrue(plan(request(ref, ExtractSelection.Entries(listOf(entryId(ref, 1, "nope.txt"))))) is ExtractPlanResult.Refused)
        assertTrue(plan(request(ref, ExtractSelection.Entries(listOf(ArchiveDocumentId.root(ref))))) is ExtractPlanResult.Refused)
        assertTrue(plan(request(ref, ExtractSelection.Entries(emptyList()))) is ExtractPlanResult.Refused)
        val other = archive("b.zip")
        assertTrue(plan(request(ref, ExtractSelection.Entries(listOf(entryId(other, 1, "docs/readme.md"))))) is ExtractPlanResult.Refused)
    }

    @Test
    fun `the structural verdict is the gate -- partial, a structural refusal, or an unreadable archive refuse everything`() {
        val partial = plan(request(archive("p.zip", ExtractTestArchives.sample(partial = true))))
        assertEquals(ExtractPlanResult.Refused(ExtractPlanner.PARTIAL_REFUSED), partial)
        val structural = plan(request(archive("s.zip", ExtractTestArchives.sample(structuralRefusal = "Archive contains a duplicate path"))))
        assertTrue(structural is ExtractPlanResult.Refused && structural.reason.contains("duplicate path"))
        stub.listFailure = io.github.mbaliga.fylz.decoder.ArchiveInspection.failed(io.github.mbaliga.fylz.decoder.ArchiveInspection.OUTCOME_CORRUPT, "Truncated")
        val unreadable = plan(request(archive("c.zip")))
        assertTrue(unreadable is ExtractPlanResult.Refused && unreadable.reason.contains("damaged"))
    }

    @Test
    fun `encryption routes a whole ZIP to the legacy path and refuses everything else`() {
        val zip = archive("e.zip", ExtractTestArchives.sample(encryptedHello = true))
        assertEquals(ExtractPlanResult.LegacyEncryptedZip, plan(request(zip)))
        assertEquals(ExtractPlanResult.Refused(ExtractPlanner.ENCRYPTED_REFUSED), plan(request(zip, ExtractSelection.Entries(listOf(entryId(zip, ExtractTestArchives.HELLO, "hello.txt"))))))
        assertTrue("a selection of plain entries inside a partly protected archive extracts normally", plan(request(zip, ExtractSelection.Entries(listOf(entryId(zip, ExtractTestArchives.LATE_X, "late/x.txt"))))) is ExtractPlanResult.Planned)
        assertEquals("a folder holding a protected entry is refused", ExtractPlanResult.Refused(ExtractPlanner.ENCRYPTED_REFUSED), plan(request(archive("e2.zip", FakeArchive(listOf(FakeArchive.Entry("d/", kind = ArchiveEntryInfo.KIND_DIRECTORY), FakeArchive.Entry("d/secret.txt", "x".toByteArray(), encrypted = true)))), ExtractSelection.Entries(listOf(ArchiveDocumentId(archive("e2.zip", FakeArchive(listOf(FakeArchive.Entry("d/", kind = ArchiveEntryInfo.KIND_DIRECTORY), FakeArchive.Entry("d/secret.txt", "x".toByteArray(), encrypted = true)))).source, emptyList(), 0, "d"))))))
        val sevenZip = archive("e.7z", ExtractTestArchives.sample(formatCode = ArchiveFormatFamily.SEVEN_ZIP, encryptedHello = true))
        assertEquals(ExtractPlanResult.Refused(ExtractPlanner.ENCRYPTED_REFUSED), plan(request(sevenZip)))
    }

    @Test
    fun `Here above the root threshold asks, and the answer decides the layout`() {
        val ref = archive()
        val questions = ArrayList<Pair<Int, String>>()
        fun ui(answer: HereChoice?) = object : PlannerUi by HeadlessPlannerUi() {
            override suspend fun chooseHereLayout(rootCount: Int, folderName: String): HereChoice? { questions += rootCount to folderName; return answer }
        }
        val folder = runBlocking { planner(threshold = 2).plan(request(ref), ui(HereChoice.INTO_FOLDER)) } as ExtractPlanResult.Planned
        assertEquals(listOf(5 to "a"), questions)
        assertEquals(ExtractLayout.INTO_FOLDER, folder.plan.layout)
        assertEquals("a", folder.plan.folderName)
        val here = runBlocking { planner(threshold = 2).plan(request(ref), ui(HereChoice.HERE)) } as ExtractPlanResult.Planned
        assertEquals(ExtractLayout.HERE, here.plan.layout)
        assertEquals(ExtractPlanResult.Cancelled, runBlocking { planner(threshold = 2).plan(request(ref), ui(null)) })
        // Headless: `largeHereAsFolder` decides, and the threshold is exclusive.
        val headless = runBlocking { planner(threshold = 2).plan(request(ref), HeadlessPlannerUi(largeHereAsFolder = true)) } as ExtractPlanResult.Planned
        assertEquals(ExtractLayout.INTO_FOLDER, headless.plan.layout)
        val notAsked = runBlocking { planner(threshold = 5).plan(request(ref), ui(null)) }
        assertTrue(notAsked is ExtractPlanResult.Planned)
        assertEquals("photos", ExtractPlanner.extractionFolderBaseName("photos.tar.gz"))
        assertEquals("photos", ExtractPlanner.extractionFolderBaseName("photos.7z"))
        assertEquals("a name that is only an extension stays itself", ".zip", ExtractPlanner.extractionFolderBaseName(".zip"))
        assertEquals("archive", ExtractPlanner.extractionFolderBaseName(""))
    }

    @Test
    fun `on vfat an entry over the file ceiling is a preflight problem whose skip removes its ordinal`() {
        volume = ExtractTestArchives.vfat()
        val big = 5L * 1024 * 1024 * 1024
        val ref = archive("v.zip", ExtractTestArchives.sample(guideDeclaredSize = big))
        var seen: PreflightResult? = null
        val ui = object : PlannerUi by HeadlessPlannerUi() {
            override suspend fun resolvePreflight(preflight: PreflightResult): PreflightDecision { seen = preflight; return HeadlessPlannerUi().resolvePreflight(preflight)!! }
        }
        val (operation, plan, summary) = planned(request(ref), ui)
        val problem = seen!!.problems.filterIsInstance<PreflightProblem.FileTooLargeForVfat>().single()
        assertEquals(entryId(ref, ExtractTestArchives.GUIDE, "docs/guide.md"), ArchiveDocumentId.parse(problem.item.sourceUri))
        assertFalse(ExtractTestArchives.GUIDE in plan.ordinals)
        assertTrue(ExtractTestArchives.README in plan.ordinals)
        assertEquals(ExtractTestArchives.readme.size + ExtractTestArchives.hello.size.toLong(), operation.items[0].expectedBytes)
        assertEquals(4L * 1024 * 1024 * 1024 - 1L, plan.limits.maxFileBytes)
        assertTrue(plan.sanitize)
        assertEquals("the plan's total no longer counts the skipped entry", ExtractTestArchives.expectedFiles.values.sumOf { it.size.toLong() } - ExtractTestArchives.guide.size, summary.totalBytes)
        // Without vfat the same archive plans the entry (the size rule is the engine's at run time);
        // at 5 GiB that plan needs the consent tick.
        volume = ExtractTestArchives.exfat()
        assertTrue(ExtractTestArchives.GUIDE in planned(request(ref), HeadlessPlannerUi(allowLarge = true)).plan.ordinals)
    }

    @Test
    fun `on a FAT family every component is sanitised and post-sanitisation collisions are uniquified`() {
        volume = ExtractTestArchives.exfat()
        val ref = archive(
            "f.zip",
            FakeArchive(
                listOf(
                    FakeArchive.Entry("a?b.txt", "1".toByteArray()),
                    FakeArchive.Entry("a*b.txt", "2".toByteArray()),
                    FakeArchive.Entry("dir/inner:name.txt", "3".toByteArray()),
                    FakeArchive.Entry("trailing.", "4".toByteArray()),
                ),
            ),
        )
        val (operation, plan, summary) = planned(request(ref))
        assertTrue(plan.sanitize)
        assertEquals(listOf("a_b.txt", "a_b (2).txt", "dir", "trailing_"), operation.items.map { it.displayName })
        assertEquals(listOf("a?b.txt", "a*b.txt", "dir", "trailing."), plan.items.map { it.rootPath })
        assertEquals("three top-level names and one nested component changed", 4, summary.sanitizedComponents)
        volume = ExtractTestArchives.ext4()
        val untouched = planned(request(ref))
        assertFalse(untouched.plan.sanitize)
        assertEquals(listOf("a?b.txt", "a*b.txt", "dir", "trailing."), untouched.operation.items.map { it.displayName })
        assertEquals(0, untouched.summary.sanitizedComponents)
    }

    @Test
    fun `conflicts come from one destination listing and each resolution lands in the plan item`() {
        File(destinationDir, "hello.txt").writeText("old")
        File(destinationDir, "docs").mkdirs()
        val ref = archive()
        val seen = ArrayList<ExtractConflict>()
        val ui = object : PlannerUi by HeadlessPlannerUi() {
            override suspend fun resolveConflicts(conflicts: List<ExtractConflict>): Map<Int, ConflictPolicy> {
                seen += conflicts
                return mapOf(0 to ConflictPolicy.KEEP_BOTH, 1 to ConflictPolicy.REPLACE)
            }
        }
        val (_, plan, _) = planned(request(ref), ui)
        assertEquals(listOf("docs", "hello.txt"), seen.map { it.name })
        assertEquals(listOf(true, false), seen.map { it.isDirectory })
        assertEquals(ExtractTestArchives.hello.size.toLong(), seen[1].bytes)
        assertEquals("hello.txt", seen[1].existing.name)
        assertEquals(ExtractPlanItem(0, "docs", "docs", ConflictPolicy.KEEP_BOTH, "docs (2)"), plan.items[0])
        assertEquals(ExtractPlanItem(1, "hello.txt", "hello.txt", ConflictPolicy.REPLACE, null), plan.items[1])
        assertEquals(ExtractPlanItem(2, "images", "images", ConflictPolicy.SKIP, null), plan.items[2])
        // Headless: the constructor's policy for every conflict; SKIP by default.
        val skip = planned(request(ref))
        assertEquals(listOf(ConflictPolicy.SKIP, ConflictPolicy.SKIP, ConflictPolicy.SKIP, ConflictPolicy.SKIP, ConflictPolicy.SKIP), skip.plan.items.map { it.conflictPolicy })
        val replace = planned(request(ref), HeadlessPlannerUi(conflictPolicy = ConflictPolicy.REPLACE_IF_NEWER))
        assertEquals(ConflictPolicy.REPLACE_IF_NEWER, replace.plan.items[1].conflictPolicy)
        // A cancel from the sheet cancels the plan.
        val cancelling = object : PlannerUi by HeadlessPlannerUi() {
            override suspend fun resolveConflicts(conflicts: List<ExtractConflict>): Map<Int, ConflictPolicy>? = null
        }
        assertEquals(ExtractPlanResult.Cancelled, plan(request(ref), cancelling))
    }

    @Test
    fun `above the consent thresholds the plan needs the tick -- refused headless, allowed with allowLarge, and the limits relax`() {
        val big = 5L * 1024 * 1024 * 1024
        val ref = archive("big.zip", ExtractTestArchives.sample(guideDeclaredSize = big))
        val refused = plan(request(ref))
        assertTrue(refused is ExtractPlanResult.Refused && refused.reason.contains("confirmation"))
        val (_, plan, summary) = planned(request(ref), HeadlessPlannerUi(allowLarge = true))
        assertTrue(summary.needsConsent)
        assertTrue(plan.consent)
        assertEquals(200_000, plan.limits.maxEntries)
        assertEquals(ArchiveLimits.forExtraction(volume, consent = true), plan.limits)
        assertEquals(big + ExtractTestArchives.expectedFiles.values.sumOf { it.size.toLong() } - ExtractTestArchives.guide.size, summary.totalBytes)
        // A UI that declines the tick cancels; one that ticks consents.
        val declined = object : PlannerUi by HeadlessPlannerUi() {
            override suspend fun confirm(summary: ExtractSummary): Boolean = false
        }
        assertEquals(ExtractPlanResult.Cancelled, plan(request(ref), declined))
        val ticked = object : PlannerUi by HeadlessPlannerUi() {
            override suspend fun confirm(summary: ExtractSummary): Boolean = true
        }
        assertTrue(planned(request(ref), ticked).plan.consent)
        // Below the thresholds no consent is recorded even when the UI says yes.
        assertFalse(planned(request(archive()), ticked).plan.consent)
    }

    @Test
    fun `a destination that is missing or not a folder is refused`() {
        val ref = archive()
        val missing = ExtractRequest(ref, ExtractSelection.All, ExtractTestArchives.treeUri("nowhere"), ExtractLayoutRequest.Here)
        assertTrue(plan(missing) is ExtractPlanResult.Refused)
    }

    @Test
    fun `an insufficient-space verdict is refused headless and left to the UI otherwise`() {
        volume = ExtractTestArchives.ext4(free = 100L)
        val ref = archive()
        val refused = plan(request(ref))
        assertTrue(refused is ExtractPlanResult.Refused && refused.reason.contains("space"))
        assertTrue(plan(request(ref), HeadlessPlannerUi(proceedDespiteSpace = true)) is ExtractPlanResult.Planned)
        assertEquals(0L, planned(request(ref), HeadlessPlannerUi(proceedDespiteSpace = true)).plan.limits.maxTotalUncompressedBytes)
    }

    @Test
    fun `the plan round trips through the journal exactly`() {
        val ref = archive()
        val (operation, plan, _) = planned(request(ref))
        val journal = OperationJournal(context)
        journal.putWithExtractPlan(operation, plan)
        assertEquals(plan, journal.extractPlan(operation.id))
        assertEquals(operation.items, journal.find(operation.id)!!.items)
        assertTrue(OperationRetryPolicy.isPlannedExtract(journal.find(operation.id)!!))
    }
}
