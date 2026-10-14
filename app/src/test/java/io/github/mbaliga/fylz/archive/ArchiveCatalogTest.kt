package io.github.mbaliga.fylz.archive

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * `ArchiveCatalog` (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.3) over the hosted file
 * provider and [FakeArchiveDecoder]: single-flight, disk-first with the summary sidecar (fail
 * closed), keys, corrupt-file rebuild, memoised failures, the LRU of two, the listing budget, the
 * pinned source, and nested archives to the depth bound.
 */
class ArchiveCatalogTest : FylzDocumentsProviderTestBase() {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val limits = ArchiveLimits()
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var sweeper: ArchiveCacheSweeper
    private lateinit var entryCache: ArchiveEntryCache
    private lateinit var pipeProvider: PipeDocumentsProvider

    private val sample = FakeArchive(
        listOf(
            FakeArchive.Entry("docs/", kind = ArchiveEntryInfo.KIND_DIRECTORY),
            FakeArchive.Entry("docs/readme.md", "# readme\n".toByteArray()),
            FakeArchive.Entry("hello.txt", "hello world".toByteArray()),
            FakeArchive.Entry("images/pixel.bin", ByteArray(256) { it.toByte() }),
        ),
    )

    /** Robolectric's `StatFs` reports no free space, so the space checks get an injected answer, as in `ArchiveSourceTest`. */
    private val plenty: () -> Long? = { Long.MAX_VALUE }

    @Before
    fun setUp() {
        stub = FakeArchiveDecoder()
        sweeper = ArchiveCacheSweeper(context)
        entryCache = ArchiveEntryCache(context, FakeArchiveDecoder.client(stub), limits, sweeper, availableCacheBytes = plenty)
        pipeProvider = PipeDocumentsProvider.install()
    }

    private fun localSource(isSeekable: (android.os.ParcelFileDescriptor) -> Boolean = { it.statSize >= 0L }) =
        ArchiveSource(context, limits, isSeekable = isSeekable, availableCacheBytes = plenty)

    private fun catalog(maxTrees: Int = 2, sweeper: ArchiveCacheSweeper = this.sweeper, source: ArchiveSource = localSource()) =
        ArchiveCatalog(context, source, FakeArchiveDecoder.client(stub), limits, entryCache, sweeper, maxTrees = maxTrees)

    private fun localUri(name: String, archive: FakeArchive = sample): Uri {
        archive.write(File(rootDir, name))
        return FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, name)
    }

    private fun listings(): List<File> = sweeper.listingsRoot.listFiles().orEmpty().toList()

    @Test
    fun `an archive lists once, the tree is built, and the listing and its summary are on disk`() = runBlocking<Unit> {
        val catalog = catalog()
        val handle = catalog.open(ArchiveRef(localUri("a.zip"), emptyList()))
        assertEquals(listOf("docs", "hello.txt", "images"), handle.tree.children("").map { it.name })
        assertEquals(1, stub.listCalls.get())
        assertEquals(1, catalog.listingCount)
        assertTrue(handle.summary.policyAllowed)
        assertFalse(handle.partial)
        assertEquals(0, handle.quarantined)
        assertFalse(handle.staged)
        assertTrue(handle.source is PinnedSource.Document)
        assertEquals(setOf("${handle.key}.fzl", "${handle.key}${ArchiveCatalog.SUMMARY_SUFFIX}"), listings().map { it.name }.toSet())
        assertTrue(listings().none { it.name.endsWith(ArchiveCacheSweeper.PART_SUFFIX) })
        // The raw path the fill will send is the listing's, not the tree's.
        assertEquals("docs/readme.md", handle.rawPathOf(handle.tree.entry("docs/readme.md")!!))
    }

    @Test
    fun `two concurrent opens of one archive produce one listing`() = runBlocking<Unit> {
        stub.listDelayMillis = 150
        val catalog = catalog()
        val ref = ArchiveRef(localUri("a.zip"), emptyList())
        val handles = listOf(async { catalog.open(ref) }, async { catalog.open(ref) }, async { catalog.open(ref) }).awaitAll()
        assertEquals(1, stub.listCalls.get())
        assertTrue(handles.all { it === handles[0] })
    }

    @Test
    fun `a warm fzl with its summary is read from disk without the decoder, and the summary is what was listed`() = runBlocking<Unit> {
        val uri = localUri("a.zip", sample.copyWith(policyAllowed = false, policyReason = "Archive contains an unsafe path segment."))
        val first = catalog().open(ArchiveRef(uri, emptyList()))
        assertFalse(first.summary.policyAllowed)
        assertEquals(1, stub.listCalls.get())
        // A fresh catalog (a cold process) has no tree in memory: the disk copy serves it.
        val second = catalog().open(ArchiveRef(uri, emptyList()))
        assertEquals("no second listing", 1, stub.listCalls.get())
        assertEquals(first.key, second.key)
        assertEquals(first.tree.children("").map { it.path }, second.tree.children("").map { it.path })
        assertFalse(second.summary.policyAllowed)
        assertEquals("Archive contains an unsafe path segment.", second.summary.policyReason)
        assertEquals(first.summary.formatCode, second.summary.formatCode)
    }

    @Test
    fun `a fzl without a readable summary fails closed and is re-listed`() = runBlocking<Unit> {
        val uri = localUri("a.zip")
        val first = catalog().open(ArchiveRef(uri, emptyList()))
        val summaryFile = File(sweeper.listingsRoot, "${first.key}${ArchiveCatalog.SUMMARY_SUFFIX}")
        assertTrue(summaryFile.delete())
        catalog().open(ArchiveRef(uri, emptyList()))
        assertEquals("re-listed", 2, stub.listCalls.get())
        assertTrue(summaryFile.isFile)
        // An unreadable summary is the same as a missing one.
        summaryFile.writeText("{not json")
        catalog().open(ArchiveRef(uri, emptyList()))
        assertEquals(3, stub.listCalls.get())
        assertTrue(ArchiveCatalog.decodeSummary(summaryFile.readText()).policyAllowed)
    }

    @Test
    fun `a corrupt fzl is rebuilt once`() = runBlocking<Unit> {
        val uri = localUri("a.zip")
        val first = catalog().open(ArchiveRef(uri, emptyList()))
        val listingFile = File(sweeper.listingsRoot, "${first.key}.fzl")
        val good = listingFile.readBytes()
        listingFile.writeBytes(good.copyOf(good.size - 3))
        val second = catalog().open(ArchiveRef(uri, emptyList()))
        assertEquals(2, stub.listCalls.get())
        assertArrayEquals(good, listingFile.readBytes())
        assertEquals(first.tree.size, second.tree.size)
        catalog().open(ArchiveRef(uri, emptyList()))
        assertEquals("the rebuilt file is good", 2, stub.listCalls.get())
    }

    @Test
    fun `the key changes with the file's size or mtime, and a nested key chains from its outer key`() = runBlocking<Unit> {
        val uri = localUri("a.zip")
        val catalog = catalog()
        val first = catalog.open(ArchiveRef(uri, emptyList()))
        // Replace the file with a different-sized one: new key, new listing.
        sample.copyWith(extra = FakeArchive.Entry("more.txt", "more".toByteArray())).write(File(rootDir, "a.zip"))
        val second = catalog.open(ArchiveRef(uri, emptyList()))
        assertNotEquals(first.key, second.key)
        assertEquals(2, stub.listCalls.get())
        assertEquals(4, second.tree.children("").size)
        // Nested: the inner key is derived from the outer one.
        val inner = FakeArchive(listOf(FakeArchive.Entry("inner.txt", "inner".toByteArray())))
        val outerUri = localUri("outer.zip", FakeArchive(listOf(FakeArchive.Entry("nested.zip", FakeArchive.bytes(inner)))))
        val outer = catalog.open(ArchiveRef(outerUri, emptyList()))
        val nested = catalog.open(ArchiveRef(outerUri, listOf("nested.zip")))
        assertNotEquals(outer.key, nested.key)
        assertEquals(listOf("inner.txt"), nested.tree.children("").map { it.name })
        assertTrue(nested.source is PinnedSource.Materialised)
        assertEquals(
            ArchiveCatalog.sha256("${outer.key}|nested.zip|${outer.tree.entry("nested.zip")!!.uncompressedBytes}|${outer.tree.entry("nested.zip")!!.mtimeEpochSeconds}"),
            nested.key,
        )
    }

    @Test
    fun `a failure is memoised until refresh, and never loops against the decoder`() = runBlocking<Unit> {
        stub.listFailure = ArchiveInspection.failed(ArchiveInspection.OUTCOME_CORRUPT, "Truncated input file")
        val uri = localUri("a.zip")
        val catalog = catalog()
        val failure = assertThrows(ArchiveCatalog.Failure.Refused::class.java) { runBlocking { catalog.open(ArchiveRef(uri, emptyList())) } }
        assertEquals("The archive is damaged or could not be read: Truncated input file", failure.message)
        assertEquals(ArchiveInspection.OUTCOME_CORRUPT, failure.outcome)
        val again = assertThrows(ArchiveCatalog.Failure.Refused::class.java) { runBlocking { catalog.open(ArchiveRef(uri, emptyList())) } }
        assertTrue(again === failure)
        assertEquals("memoised: one call", 1, stub.listCalls.get())
        assertTrue("no .part left", listings().isEmpty())
        stub.listFailure = null
        catalog.forgetFailures()
        catalog.open(ArchiveRef(uri, emptyList()))
        assertEquals(2, stub.listCalls.get())
    }

    @Test
    fun `a vanished source is SourceFailed with the source's message`() = runBlocking<Unit> {
        val catalog = catalog()
        val gone = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "gone.zip")
        val failure = assertThrows(ArchiveCatalog.Failure.SourceFailed::class.java) { runBlocking { catalog.open(ArchiveRef(gone, emptyList())) } }
        assertTrue(failure.message!!.startsWith("Unable to read the archive"))
        assertTrue(failure.cause is ArchiveSourceException.Unreadable)
        assertEquals(0, stub.listCalls.get())
    }

    @Test
    fun `the in-memory LRU keeps two trees and releases the third`() = runBlocking<Unit> {
        val catalog = catalog(maxTrees = 2)
        // The Uris are built once: `localUri` rewrites the file, which would change the key.
        val (uriA, uriB, uriC) = Triple(localUri("a.zip"), localUri("b.zip"), localUri("c.zip"))
        val a = catalog.open(ArchiveRef(uriA, emptyList()))
        val b = catalog.open(ArchiveRef(uriB, emptyList()))
        assertTrue(a.retain()); a.release()
        catalog.open(ArchiveRef(uriC, emptyList()))
        assertFalse("a was evicted and closed", a.retain())
        assertTrue("b is still held", b.retain()); b.release()
        // Re-opening a comes from disk, not the decoder.
        val calls = stub.listCalls.get()
        val aAgain = catalog.open(ArchiveRef(uriA, emptyList()))
        assertEquals(calls, stub.listCalls.get())
        assertTrue(aAgain.retain()); aAgain.release()
    }

    @Test
    fun `the listing budget evicts old listings but never the one in flight`() = runBlocking<Unit> {
        val small = ArchiveCacheSweeper(context, listingBudgetBytes = 400L)
        val catalog = catalog(sweeper = small)
        val a = catalog.open(ArchiveRef(localUri("a.zip"), emptyList()))
        val aFile = File(small.listingsRoot, "${a.key}.fzl")
        assertTrue(aFile.setLastModified(System.currentTimeMillis() - 60_000))
        val b = catalog.open(ArchiveRef(localUri("b.zip"), emptyList()))
        assertTrue(File(small.listingsRoot, "${b.key}.fzl").isFile)
        assertFalse("the older listing went", aFile.exists())
    }

    @Test
    fun `a staged source is copied once and every fill opens its own descriptor from the copy`() = runBlocking<Unit> {
        pipeProvider.bytes = File(rootDir, "s.zip").let { sample.write(it).readBytes() }
        pipeProvider.declaredSize = pipeProvider.bytes.size.toLong()
        val catalog = catalog(source = localSource(isSeekable = { false }))
        val handle = catalog.open(ArchiveRef(PipeDocumentsProvider.documentUri(), emptyList()))
        assertTrue(handle.staged)
        assertTrue(handle.source is PinnedSource.Staged)
        assertEquals("one probe descriptor and one stream", 2, pipeProvider.openCount)
        val hello = entryCache.materialise(handle, handle.tree.entry("hello.txt")!!)
        val readme = entryCache.materialise(handle, handle.tree.entry("docs/readme.md")!!)
        assertEquals("hello world", hello.readText())
        assertEquals("# readme\n", readme.readText())
        assertEquals("the provider was not asked again", 2, pipeProvider.openCount)
        val staged = (handle.source as PinnedSource.Staged).file
        assertTrue(staged.isFile)
        // Two descriptors from one call each: distinct, both positioned at 0.
        val d1 = handle.openDescriptor()
        val d2 = handle.openDescriptor()
        assertNotEquals(d1.fd, d2.fd)
        d1.close(); d2.close()
    }

    @Test
    fun `nested archives open to depth four and the fifth level is refused`() = runBlocking<Unit> {
        var inner = FakeArchive(listOf(FakeArchive.Entry("innermost.txt", "deep".toByteArray())))
        repeat(4) { level -> inner = FakeArchive(listOf(FakeArchive.Entry("level${5 - level}.zip", FakeArchive.bytes(inner)))) }
        val uri = localUri("nested5.zip", inner)
        val catalog = catalog()
        val depth4 = catalog.open(ArchiveRef(uri, listOf("level2.zip", "level3.zip", "level4.zip")))
        assertEquals(listOf("level5.zip"), depth4.tree.children("").map { it.name })
        assertThrows(ArchiveCatalog.Failure.TooDeep::class.java) {
            runBlocking { catalog.open(ArchiveRef(uri, listOf("level2.zip", "level3.zip", "level4.zip", "level5.zip"))) }
        }
        assertThrows(ArchiveCatalog.Failure.NotAnEntry::class.java) {
            runBlocking { catalog.open(ArchiveRef(uri, listOf("missing.zip"))) }
        }
        // The materialised inner archives are pinned while their handles live.
        assertTrue(entryCache.pinnedFiles().isNotEmpty())
    }

    @Test
    fun `a partial listing is served with its flag`() = runBlocking<Unit> {
        val uri = localUri("damaged.tar", sample.copyWith(partial = true))
        val handle = catalog().open(ArchiveRef(uri, emptyList()))
        assertTrue(handle.partial)
        assertEquals("Damaged tar archive", handle.summary.partialMessage)
        assertEquals("four rows plus the synthesised images/", 5, handle.tree.size)
        assertNull(handle.summary.structuralRefusal)
    }

    @Test
    fun `the summary sidecar round-trips every field`() {
        val summary = ArchiveInspection(
            outcome = 0, message = null, formatCode = 0x30000, formatName = "GNU tar format", filters = listOf("gzip"),
            archiveBytes = 12L, entryCount = 3, fileCount = 2, directoryCount = 1, linkCount = 0, totalUncompressedBytes = -1L,
            hasEncryptedEntries = true, hasEncryptedMetadata = false, hasLossyNames = true, policyAllowed = false,
            policyReason = "Archive contains an entry with unknown size.", rows = emptyList(), rowsTruncated = false,
            partial = true, partialMessage = "Damaged tar archive", structuralRefusal = "Archive contains an entry with unknown size.",
        )
        assertEquals(summary, ArchiveCatalog.decodeSummary(ArchiveCatalog.encodeSummary(summary)))
        assertThrows(IllegalArgumentException::class.java) { ArchiveCatalog.decodeSummary("{}") }
        assertThrows(IllegalArgumentException::class.java) { ArchiveCatalog.decodeSummary("nope") }
    }

    private fun FakeArchive.copyWith(
        partial: Boolean = this.partial,
        policyAllowed: Boolean = this.policyAllowed,
        policyReason: String? = this.policyReason,
        extra: FakeArchive.Entry? = null,
    ) = FakeArchive(entries + listOfNotNull(extra), partial, policyAllowed, policyReason, structuralRefusal, formatCode)
}
