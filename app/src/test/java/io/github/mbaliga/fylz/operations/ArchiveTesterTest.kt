package io.github.mbaliga.fylz.operations

import android.content.Context
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ExtractFrameReader
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `ArchiveTester` (MASTER_PLAN M3.8): a clean archive reports every entry passed; a known-bad CRC
 * fails only that entry and leaves the rest passed; a cancel mid-pass stops early with whatever
 * was already tested, and -- the point of the whole feature -- never creates a single file
 * anywhere, win, lose or cancel; an encrypted archive is `PasswordRequired`, never prompted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveTesterTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var rootDir: File
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var catalog: ArchiveCatalog
    private val unbinds = AtomicInteger()

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        val faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(VolumeDescriptor(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Internal storage", rootDir, primary = true, removable = false, readOnly = false))
        stub = FakeArchiveDecoder()
        catalog = ExtractTestArchives.catalog(context, stub)
    }

    private fun tester() = ArchiveTester(
        catalog = catalog,
        extractionClient = { FakeArchiveDecoder.client(stub, onUnbind = { unbinds.incrementAndGet() }) },
        streamInactivityMillis = 5_000L,
        cancelWaitMillis = 500L,
    )

    /** Every regular file directly under [rootDir], so a test can assert none of them are new. */
    private fun rootFiles(): List<String> = rootDir.list()?.toList().orEmpty().sorted()

    @Test
    fun `a clean archive reports every entry passed and creates nothing`() {
        val before = rootFiles()
        val ref = ExtractTestArchives.write(rootDir, "clean.zip", ExtractTestArchives.sample())
        val outcome = runBlocking { tester().test(ref) }
        check(outcome is ArchiveTestOutcome.Completed) { "expected Completed, got $outcome" }
        assertFalse(outcome.cancelled)
        // docs/, docs/readme.md, docs/guide.md, hello.txt (read once for the hardlink target too),
        // images/pixel.bin, late/x.txt, late/, bad?name.txt -- the symlink and the hardlink's own
        // entry carry no data and are not tested separately.
        assertEquals(8, outcome.entries.size)
        assertTrue(outcome.entries.all { it.outcome is EntryOutcome.Passed })
        assertEquals(setOf("docs/", "docs/readme.md", "docs/guide.md", "hello.txt", "images/pixel.bin", "late/x.txt", "late/", "bad?name.txt"), outcome.entries.map { it.path }.toSet())
        assertEquals("nothing under the hosted root but the archive itself", before + "clean.zip", rootFiles())
        assertEquals(1, unbinds.get())
    }

    @Test
    fun `a known-bad CRC fails only that entry, the rest pass, and nothing is created`() {
        stub.failOrdinals[ExtractTestArchives.GUIDE] = ExtractFrameReader.FAIL_CRC
        val before = rootFiles()
        val ref = ExtractTestArchives.write(rootDir, "crc-bad.zip", ExtractTestArchives.sample())
        val outcome = runBlocking { tester().test(ref) }
        check(outcome is ArchiveTestOutcome.Completed) { "expected Completed, got $outcome" }
        assertFalse(outcome.cancelled)
        assertEquals(8, outcome.entries.size)
        val guide = outcome.entries.single { it.path == "docs/guide.md" }
        val failure = guide.outcome
        check(failure is EntryOutcome.Failed) { "expected guide.md to fail, was $failure" }
        assertEquals(ExtractFrameReader.FAIL_CRC, failure.kind)
        assertEquals("CRC mismatch", failure.kindLabel)
        assertTrue(outcome.entries.filter { it.path != "docs/guide.md" }.all { it.outcome is EntryOutcome.Passed })
        assertEquals(before + "crc-bad.zip", rootFiles())
    }

    @Test
    fun `cancel mid-test stops early with whatever was tested, and still creates nothing`() {
        val cancelRequested = AtomicBoolean(false)
        // Set as soon as GUIDE's own data is under way -- whether the cutoff lands exactly there or
        // at the next entry (DiscardSink.begin's own per-entry check, or the streaming call's own
        // coarser poll, whichever notices first) is not the point of this test; that it stops
        // early, cleanly, having created nothing, is.
        stub.beforeData = { ordinal -> if (ordinal == ExtractTestArchives.GUIDE) cancelRequested.set(true); true }
        val before = rootFiles()
        val ref = ExtractTestArchives.write(rootDir, "cancel.zip", ExtractTestArchives.sample())
        val outcome = runBlocking { tester().test(ref, cancelled = { cancelRequested.get() }) }
        check(outcome is ArchiveTestOutcome.Completed) { "expected Completed, got $outcome" }
        assertTrue(outcome.cancelled)
        // Not every one of the archive's 8 entries: it genuinely stopped early.
        assertTrue("expected an early stop, got ${outcome.entries.size} entries", outcome.entries.size < 8)
        // There is nothing to leave behind, by construction: DiscardSink never opens a destination.
        assertEquals(before + "cancel.zip", rootFiles())
    }

    @Test
    fun `an encrypted archive is password-required, never prompted, and nothing is read`() {
        val ref = ExtractTestArchives.write(rootDir, "secret.zip", ExtractTestArchives.sample(encryptedHello = true))
        val outcome = runBlocking { tester().test(ref) }
        assertEquals(ArchiveTestOutcome.PasswordRequired, outcome)
        assertEquals(0, stub.rangesCalls.size)
    }

    @Test
    fun `a damaged (partial) archive is refused rather than tested with a hole in it`() {
        val ref = ExtractTestArchives.write(rootDir, "partial.zip", ExtractTestArchives.sample(partial = true))
        val outcome = runBlocking { tester().test(ref) }
        check(outcome is ArchiveTestOutcome.Refused) { "expected Refused, got $outcome" }
        assertEquals(0, stub.rangesCalls.size)
    }
}
