package io.github.mbaliga.fylz.archive

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.data.ArchiveSpacePolicy
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * `ArchiveSource` (docs/agent/DESIGN-M32-SEEKABLE-PFD.md section 2.3 and 2.8). The headline is
 * the first case: a local file resolves to the provider's own descriptor and **nothing is staged**
 * -- no whole-archive copy, the thing the milestone is named for. The rest drive the staging path
 * through [PipeDocumentsProvider] with `isSeekable = { false }` (Robolectric's file-backed pipe
 * would otherwise report a size), and every refusal is checked to leave no workspace behind. The
 * 24 h sweep of stale workspaces moved to `ArchiveCacheSweeper` in M3.3a (`ArchiveCacheSweeperTest`).
 */
class ArchiveSourceTest : FylzDocumentsProviderTestBase() {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val limits = ArchiveLimits(maxArchiveBytes = 1L * 1024L * 1024L)
    private val payload = ByteArray(40 * 1024) { (it * 31 + 7).toByte() }

    private lateinit var pipeProvider: PipeDocumentsProvider

    @Before
    fun installPipeProvider() {
        pipeProvider = PipeDocumentsProvider.install().apply { bytes = payload }
    }

    private fun workRoot(): File = File(context.cacheDir, ArchiveSource.WORK_DIRECTORY)

    private fun workspaces(): List<File> = workRoot().listFiles().orEmpty().toList()

    private fun localArchiveUri(): Uri {
        File(rootDir, "sample.zip").writeBytes(payload)
        return FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "sample.zip")
    }

    private fun stagingSource(
        availableCacheBytes: () -> Long? = { Long.MAX_VALUE },
        recheckInterval: Long = ArchiveSource.SPACE_RECHECK_INTERVAL_BYTES,
    ) = ArchiveSource(
        context,
        limits,
        isSeekable = { false },
        availableCacheBytes = availableCacheBytes,
        spaceRecheckIntervalBytes = recheckInterval,
    )

    private inline fun <reified E : ArchiveSourceException> assertResolveFails(source: ArchiveSource, uri: Uri): E {
        try {
            runBlocking { source.resolve(uri) }.close()
        } catch (e: ArchiveSourceException) {
            assertTrue("expected ${E::class.simpleName}, got $e", e is E)
            return e as E
        }
        fail("expected ${E::class.simpleName}, but resolve succeeded")
        error("unreachable")
    }

    // ------------------------------------------------------------------------------------------

    @Test
    fun `a local file resolves to the provider's own descriptor and nothing is staged`() = runBlocking {
        val source = ArchiveSource(context, limits) // the real `statSize >= 0` probe
        val resolved = source.resolve(localArchiveUri())
        try {
            assertTrue("expected Direct, got $resolved", resolved is ArchiveSource.Resolved.Direct)
            assertEquals(payload.size.toLong(), resolved.pfd.statSize)
            assertTrue("no whole-archive staging: archive-work must stay empty", workspaces().isEmpty())
        } finally {
            resolved.close()
        }
        assertTrue(workspaces().isEmpty())
    }

    @Test
    fun `a non-seekable descriptor is staged byte for byte, and close deletes the copy`() = runBlocking {
        val source = stagingSource()
        val resolved = source.resolve(PipeDocumentsProvider.documentUri())
        val staged = resolved as? ArchiveSource.Resolved.Staged ?: fail("expected Staged, got $resolved").let { error("unreachable") }
        assertTrue(staged.workspace.isDirectory)
        assertEquals(1, workspaces().size)
        assertEquals(staged.workspace, workspaces().single())
        assertArrayEquals(payload, File(staged.workspace, "input").readBytes())
        assertEquals(payload.size.toLong(), staged.pfd.statSize)
        // One descriptor was tried and refused as non-seekable, then the stream was read.
        assertEquals(2, pipeProvider.openCount)
        staged.close()
        assertFalse(staged.workspace.exists())
        assertTrue(workspaces().isEmpty())
    }

    @Test
    fun `too little cache space refuses before anything is copied`() {
        pipeProvider.declaredSize = payload.size.toLong()
        val source = stagingSource(availableCacheBytes = { payload.size.toLong() }) // no headroom
        val failure = assertResolveFails<ArchiveSourceException.InsufficientSpace>(source, PipeDocumentsProvider.documentUri())
        assertEquals(payload.size.toLong() + ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM, failure.required)
        assertEquals(payload.size.toLong(), failure.available)
        assertFalse("no workspace may exist after a refused staging", workRoot().exists() && workspaces().isNotEmpty())
        assertEquals("only the seekability probe opened the document; the stream never did", 1, pipeProvider.openCount)
    }

    @Test
    fun `unknown size reserves the whole input limit before copying`() {
        pipeProvider.declaredSize = null
        val justShort = limits.maxArchiveBytes + ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM - 1
        val failure = assertResolveFails<ArchiveSourceException.InsufficientSpace>(
            stagingSource(availableCacheBytes = { justShort }),
            PipeDocumentsProvider.documentUri(),
        )
        assertEquals(limits.maxArchiveBytes + ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM, failure.required)
        assertTrue(workspaces().isEmpty())
        // Exactly enough is fine.
        runBlocking { stagingSource(availableCacheBytes = { justShort + 1 }).resolve(PipeDocumentsProvider.documentUri()) }.close()
        assertTrue(workspaces().isEmpty())
    }

    @Test
    fun `a declared size over the limit is refused before any copy`() {
        pipeProvider.declaredSize = limits.maxArchiveBytes + 1
        val failure = assertResolveFails<ArchiveSourceException.ArchiveTooLarge>(stagingSource(), PipeDocumentsProvider.documentUri())
        assertEquals(limits.maxArchiveBytes, failure.limit)
        assertTrue(workspaces().isEmpty())
        assertEquals(1, pipeProvider.openCount)
    }

    @Test
    fun `a provider that sends more than it declared is a size mismatch with no workspace left`() {
        pipeProvider.declaredSize = 1_000L // it will send 40 KiB
        val failure = assertResolveFails<ArchiveSourceException.SizeMismatch>(stagingSource(), PipeDocumentsProvider.documentUri())
        assertEquals(1_000L, failure.declared)
        assertTrue("stopped as soon as the declared size was passed, not at the end", failure.actual in 1_001L..payload.size.toLong())
        assertTrue(workspaces().isEmpty())
    }

    @Test
    fun `a provider that sends less than it declared is a size mismatch too`() {
        pipeProvider.declaredSize = payload.size.toLong() + 5
        val failure = assertResolveFails<ArchiveSourceException.SizeMismatch>(stagingSource(), PipeDocumentsProvider.documentUri())
        assertEquals(payload.size.toLong() + 5, failure.declared)
        assertEquals(payload.size.toLong(), failure.actual)
        assertTrue(workspaces().isEmpty())
    }

    @Test
    fun `an undeclared stream that runs past the input limit is too large, with no workspace left`() {
        pipeProvider.declaredSize = null
        pipeProvider.bytes = ByteArray((limits.maxArchiveBytes + 1).toInt())
        val failure = assertResolveFails<ArchiveSourceException.ArchiveTooLarge>(stagingSource(), PipeDocumentsProvider.documentUri())
        assertEquals(limits.maxArchiveBytes, failure.limit)
        assertTrue(workspaces().isEmpty())
    }

    @Test
    fun `an undeclared stream is refused when the cache headroom vanishes mid-copy`() {
        pipeProvider.declaredSize = null
        var calls = 0
        // The preflight sees plenty; every re-check during the copy sees the headroom gone.
        val vanishing: () -> Long? = { if (calls++ == 0) Long.MAX_VALUE else ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM - 1 }
        val failure = assertResolveFails<ArchiveSourceException.InsufficientSpace>(
            stagingSource(availableCacheBytes = vanishing, recheckInterval = 4 * 1024),
            PipeDocumentsProvider.documentUri(),
        )
        assertEquals(ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM, failure.required)
        assertEquals(ArchiveSpacePolicy.MIN_TEMPORARY_HEADROOM - 1, failure.available)
        assertTrue("the re-check ran at least once during the copy", calls >= 2)
        assertTrue(workspaces().isEmpty())
    }

    /** Robolectric answers an unknown authority with a stream whose `read()` throws (an
     *  `UnsupportedOperationException`, where a device would raise `FileNotFoundException` at open):
     *  either way a provider whose stream fails mid-copy is `Unreadable`, and nothing is left behind. */
    @Test
    fun `a provider whose stream fails mid-copy is unreadable, with no workspace left`() {
        val failure = assertResolveFails<ArchiveSourceException.Unreadable>(
            stagingSource(),
            Uri.parse("content://io.github.mbaliga.fylz.test.nowhere/document/missing"),
        )
        assertTrue("the resolver's own failure is kept as the cause", failure.cause != null)
        assertTrue(workspaces().isEmpty())
    }

    /**
     * The one case Robolectric cannot host (design section 4): the *default* probe on a real pipe.
     * `ParcelFileDescriptor.createPipe()` here is file-backed and reports a size, so the default
     * `statSize >= 0` would call it seekable; on a device it is -1 and the archive is staged. Kept
     * as the record of what `DEVICE_CHECKS.md` section 17 item 2 verifies by hand.
     */
    @Ignore("Robolectric's ParcelFileDescriptor.createPipe() is file-backed and reports a size; the default statSize probe on a real pipe is device-only (DEVICE_CHECKS section 17, item 2)")
    @Test
    fun `the default probe stages a real pipe`() = runBlocking {
        val resolved = ArchiveSource(context, limits).resolve(PipeDocumentsProvider.documentUri())
        try {
            assertTrue(resolved is ArchiveSource.Resolved.Staged)
        } finally {
            resolved.close()
        }
    }
}
