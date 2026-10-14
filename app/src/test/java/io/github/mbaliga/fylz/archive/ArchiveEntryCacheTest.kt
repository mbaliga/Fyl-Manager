package io.github.mbaliga.fylz.archive

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveExtractResult
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * `ArchiveEntryCache` (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.4) through
 * [FakeArchiveDecoder]: fills, the seekable descriptor, the shared fill with refcounted waiters,
 * every refusal, hardlink resolution, `.part` cleanup, eviction and pinning -- and the amendment-1
 * case: two concurrent fills from one handle read through **separate** descriptors, so an
 * interleaving decoder cannot corrupt either.
 */
class ArchiveEntryCacheTest : FylzDocumentsProviderTestBase() {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val limits = ArchiveLimits()
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var sweeper: ArchiveCacheSweeper

    private val big = ByteArray(5_000) { (it * 31 + 7).toByte() }
    private val other = ByteArray(4_000) { (it * 17 + 3).toByte() }

    private val sample = FakeArchive(
        listOf(
            FakeArchive.Entry("hello.txt", "hello world".toByteArray()),
            FakeArchive.Entry("big.bin", big),
            FakeArchive.Entry("other.bin", other),
            FakeArchive.Entry("link", kind = ArchiveEntryInfo.KIND_SYMLINK, linkTarget = "hello.txt"),
            FakeArchive.Entry("hard", kind = ArchiveEntryInfo.KIND_HARDLINK, linkTarget = "hello.txt"),
            FakeArchive.Entry("fifo", kind = ArchiveEntryInfo.KIND_OTHER),
            FakeArchive.Entry("secret.txt", "x".toByteArray(), encrypted = true),
            FakeArchive.Entry("dir/", kind = ArchiveEntryInfo.KIND_DIRECTORY),
            FakeArchive.Entry("dir/inner.txt", "inner".toByteArray()),
            FakeArchive.Entry("implicit/child.txt", "child".toByteArray()),
        ),
    )

    @Before
    fun setUp() {
        stub = FakeArchiveDecoder(interleaveMillis = 2, chunkBytes = 64)
        sweeper = ArchiveCacheSweeper(context)
    }

    private fun cache(
        budget: Long = ArchiveCacheSweeper.ENTRY_BUDGET_BYTES,
        available: () -> Long? = { Long.MAX_VALUE },
        sweeper: ArchiveCacheSweeper = this.sweeper,
        limits: ArchiveLimits = this.limits,
    ) = ArchiveEntryCache(context, FakeArchiveDecoder.client(stub), limits, sweeper, budgetBytes = budget, availableCacheBytes = available)

    private fun open(archive: FakeArchive = sample, name: String = "a.zip", entryCache: ArchiveEntryCache): ArchiveHandle = runBlocking {
        archive.write(File(rootDir, name))
        val uri: Uri = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, name)
        // Robolectric's `StatFs` reports no free space: the source gets an injected answer, as in `ArchiveSourceTest`.
        val source = ArchiveSource(context, limits, availableCacheBytes = { Long.MAX_VALUE })
        ArchiveCatalog(context, source, FakeArchiveDecoder.client(stub), limits, entryCache, sweeper)
            .open(ArchiveRef(uri, emptyList()))
    }

    private fun readAll(file: File) = file.readBytes()

    @Test
    fun `a fill materialises the entry byte for byte and a second open reuses it`() = runBlocking<Unit> {
        val cache = cache()
        val handle = open(entryCache = cache)
        val file = cache.materialise(handle, handle.tree.entry("big.bin")!!)
        assertArrayEquals(big, readAll(file))
        assertEquals(cache.fileFor(handle.key, 1), file)
        assertEquals(1, stub.extractCalls.get())
        cache.materialise(handle, handle.tree.entry("big.bin")!!)
        assertEquals("cached: no second fill", 1, stub.extractCalls.get())
        assertTrue(File(file.parentFile, "").listFiles()!!.none { it.name.endsWith(ArchiveCacheSweeper.PART_SUFFIX) })
    }

    @Test
    fun `the returned descriptor is a seekable regular file readable at an offset`() = runBlocking<Unit> {
        val cache = cache()
        val handle = open(entryCache = cache)
        val pfd = cache.open(handle, handle.tree.entry("big.bin")!!)
        try {
            assertEquals(big.size.toLong(), pfd.statSize)
            val channel = FileInputStream(pfd.fileDescriptor).channel
            channel.position(4_000L)
            val buffer = ByteBuffer.allocate(1_000)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            assertArrayEquals(big.copyOfRange(4_000, 5_000), buffer.array())
        } finally {
            pfd.close()
        }
    }

    @Test
    fun `two concurrent fills of different entries read through separate descriptors and come out byte-exact`() = runBlocking<Unit> {
        // The fake decoder positions its descriptor's offset before every 64-byte chunk and sleeps
        // in between; on one shared open file description the two calls would read each other's
        // positions. Every fill opens its own descriptor (PinnedSource), so neither is corrupted.
        val cache = cache()
        val handle = open(entryCache = cache)
        val bigEntry = handle.tree.entry("big.bin")!!
        val otherEntry = handle.tree.entry("other.bin")!!
        val (bigFile, otherFile) = listOf(
            async(Dispatchers.IO) { cache.materialise(handle, bigEntry) },
            async(Dispatchers.IO) { cache.materialise(handle, otherEntry) },
        ).awaitAll()
        assertArrayEquals(big, readAll(bigFile))
        assertArrayEquals(other, readAll(otherFile))
        assertEquals(2, stub.extractCalls.get())
    }

    @Test
    fun `concurrent opens of one entry share a fill, and a cancelled waiter does not abort it`() = runBlocking<Unit> {
        val cache = cache()
        val handle = open(entryCache = cache)
        val entry = handle.tree.entry("big.bin")!!
        val scope = CoroutineScope(Dispatchers.IO)
        val first = scope.async { cache.materialise(handle, entry) }
        val second = scope.async { cache.materialise(handle, entry) }
        delay(10)
        second.cancel()
        val file = first.await()
        assertArrayEquals(big, readAll(file))
        assertEquals("one fill shared by both", 1, cache.fillCount)
        assertEquals(1, stub.extractCalls.get())
        // Cancelling the LAST waiter aborts the fill and leaves no .part behind.
        val cache2 = cache()
        val handle2 = open(name = "b.zip", entryCache = cache2)
        val entry2 = handle2.tree.entry("other.bin")!!
        val only = scope.async { cache2.materialise(handle2, entry2) }
        delay(10)
        only.cancel()
        runCatching { only.await() }
        delay(100)
        val dir = cache2.fileFor(handle2.key, entry2.ordinal).parentFile!!
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(ArchiveCacheSweeper.PART_SUFFIX) })
    }

    @Test
    fun `the refusals come before any byte moves`() = runBlocking<Unit> {
        val cache = cache()
        val handle = open(entryCache = cache)
        fun refused(path: String): String {
            val failure = assertThrows(ArchiveEntryCache.Refused::class.java) { runBlocking { cache.materialise(handle, handle.tree.entry(path)!!) } }
            return failure.message!!
        }
        assertEquals(ArchiveEntryCache.LINKS_REFUSED, refused("link"))
        assertEquals(ArchiveEntryCache.LINKS_REFUSED, refused("fifo"))
        assertEquals(ArchiveEntryCache.ENCRYPTED_REFUSED, refused("secret.txt"))
        assertEquals("Folders cannot be opened as files.", refused("dir"))
        assertEquals("Folders cannot be opened as files.", refused("implicit"))
        assertEquals(0, stub.extractCalls.get())
        // Too large: over min(maxFileBytes, budget).
        val tiny = cache(budget = 100L)
        val tinyHandle = open(name = "t.zip", entryCache = tiny)
        assertEquals(
            ArchiveEntryCache.TOO_LARGE_REFUSED,
            assertThrows(ArchiveEntryCache.Refused::class.java) { runBlocking { tiny.materialise(tinyHandle, tinyHandle.tree.entry("big.bin")!!) } }.message,
        )
        val cramped = cache(available = { 0L })
        assertEquals(
            ArchiveEntryCache.TOO_LARGE_REFUSED,
            assertThrows(ArchiveEntryCache.Refused::class.java) { runBlocking { cramped.materialise(handle, handle.tree.entry("hello.txt")!!) } }.message,
        )
        val strict = cache(limits = ArchiveLimits(maxFileBytes = 10L))
        assertEquals(
            ArchiveEntryCache.TOO_LARGE_REFUSED,
            assertThrows(ArchiveEntryCache.Refused::class.java) { runBlocking { strict.materialise(handle, handle.tree.entry("hello.txt")!!) } }.message,
        )
        assertEquals(0, stub.extractCalls.get())
    }

    @Test
    fun `entries of a policy-refused archive are refused with the reason, browsing stays possible`() = runBlocking<Unit> {
        val cache = cache()
        val refusedArchive = FakeArchive(sample.entries, policyAllowed = false, policyReason = "Archive contains a suspicious compression ratio.")
        val handle = open(refusedArchive, "r.zip", cache)
        assertEquals("ten rows plus the synthesised implicit/", 11, handle.tree.size) // listed
        val failure = assertThrows(ArchiveEntryCache.Refused::class.java) { runBlocking { cache.materialise(handle, handle.tree.entry("hello.txt")!!) } }
        assertEquals("This archive failed safety checks: Archive contains a suspicious compression ratio.", failure.message)
        assertEquals(0, stub.extractCalls.get())
    }

    @Test
    fun `a hardlink resolves to its target's ordinal`() = runBlocking<Unit> {
        val cache = cache()
        val handle = open(entryCache = cache)
        val viaLink = cache.materialise(handle, handle.tree.entry("hard")!!)
        assertEquals("hello world", viaLink.readText())
        assertEquals(cache.fileFor(handle.key, 0), viaLink)
        val direct = cache.materialise(handle, handle.tree.entry("hello.txt")!!)
        assertEquals(viaLink, direct)
        assertEquals(1, stub.extractCalls.get())
    }

    @Test
    fun `a failed fill leaves no part file and reports the decoder's verdict`() = runBlocking<Unit> {
        val cache = cache()
        val handle = open(entryCache = cache)
        stub.extractFailure = ArchiveExtractResult.failed(ArchiveExtractResult.OUTCOME_CORRUPT, "Truncated input file")
        val failure = assertThrows(IOException::class.java) { runBlocking { cache.materialise(handle, handle.tree.entry("hello.txt")!!) } }
        assertEquals("The archive is damaged or could not be read: Truncated input file", failure.message)
        stub.extractFailure = ArchiveExtractResult.failed(ArchiveExtractResult.OUTCOME_NOT_FOUND, "no entry")
        assertEquals(
            "This entry is no longer in the archive; refresh and try again.",
            assertThrows(IOException::class.java) { runBlocking { cache.materialise(handle, handle.tree.entry("hello.txt")!!) } }.message,
        )
        // A service that reports more bytes than it wrote is not trusted either.
        stub.extractFailure = null
        stub.shortByBytes = 3
        assertTrue(
            assertThrows(IOException::class.java) { runBlocking { cache.materialise(handle, handle.tree.entry("hello.txt")!!) } }
                .message!!.startsWith("The entry was not written completely"),
        )
        stub.shortByBytes = 0
        val dir = cache.fileFor(handle.key, 0).parentFile!!
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(ArchiveCacheSweeper.PART_SUFFIX) })
        assertFalse(cache.fileFor(handle.key, 0).exists())
        // And after the failures, a good fill works.
        assertEquals("hello world", cache.materialise(handle, handle.tree.entry("hello.txt")!!).readText())
    }

    @Test
    fun `a declared size the bytes do not match is refused at the fill`() = runBlocking<Unit> {
        val cache = cache()
        val lying = FakeArchive(listOf(FakeArchive.Entry("liar.txt", "twelve bytes".toByteArray(), declaredSize = 5L)))
        val handle = open(lying, "l.zip", cache)
        val failure = assertThrows(IOException::class.java) { runBlocking { cache.materialise(handle, handle.tree.entry("liar.txt")!!) } }
        assertTrue(failure.message, failure.message!!.startsWith("The entry's size"))
        assertFalse(cache.fileFor(handle.key, 0).exists())
    }

    @Test
    fun `eviction keeps the cache under budget by mtime and never evicts a pinned file`() = runBlocking<Unit> {
        // 5,000 + 4,000 bytes against an 8,000-byte budget: the older of the two must go.
        val small = ArchiveCacheSweeper(context, entryBudgetBytes = 8_000L)
        val cache = cache(sweeper = small)
        val handle = open(entryCache = cache)
        val bigFile = cache.materialise(handle, handle.tree.entry("big.bin")!!)
        assertTrue(bigFile.setLastModified(System.currentTimeMillis() - 60_000))
        val otherFile = cache.materialise(handle, handle.tree.entry("other.bin")!!)
        assertTrue(otherFile.isFile)
        assertFalse("big (older) was evicted to stay under 8 KB", bigFile.exists())
        // Pinned: the same sequence with big pinned evicts nothing of it (the just-filled file is protected too).
        val cache2 = cache(sweeper = ArchiveCacheSweeper(context, entryBudgetBytes = 8_000L))
        val handle2 = open(name = "b.zip", entryCache = cache2)
        val pinned = cache2.materialise(handle2, handle2.tree.entry("big.bin")!!)
        cache2.pin(pinned)
        assertTrue(pinned.setLastModified(System.currentTimeMillis() - 60_000))
        cache2.materialise(handle2, handle2.tree.entry("other.bin")!!)
        assertTrue("pinned survives", pinned.exists())
        cache2.unpin(pinned)
        assertTrue(cache2.pinnedFiles().isEmpty())
    }
}
