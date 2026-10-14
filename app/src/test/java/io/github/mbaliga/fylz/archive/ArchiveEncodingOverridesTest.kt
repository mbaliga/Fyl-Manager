package io.github.mbaliga.fylz.archive

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ArchiveEncodingOverrides` (M3.7): a session-only, in-memory choice per [ArchiveRef], and its
 * [ArchiveEncodingOverrides.displayNameFor] re-decoding rule -- which never touches
 * [ArchiveTreeEntry.ordinal] or [ArchiveTreeEntry.path], the fields extraction and ids key on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveEncodingOverridesTest {

    private val archiveA = ArchiveRef(Uri.parse("content://io.github.mbaliga.fylz.files/document/primary%3ADownload%2Fa.zip"), emptyList())
    private val archiveB = ArchiveRef(Uri.parse("content://io.github.mbaliga.fylz.files/document/primary%3ADownload%2Fb.zip"), emptyList())

    private val cp437Bytes = byteArrayOf(0x63, 0x61, 0x66, 0x82.toByte(), 0x2E, 0x74, 0x78, 0x74) // "café.txt"

    private fun lossyEntry(ordinal: Int = 7, path: String = "caf�.txt") = ArchiveTreeEntry(
        path = path,
        ordinal = ordinal,
        kind = 0,
        flags = ArchiveListingCodec.FLAG_NAME_LOSSY,
        uncompressedBytes = 5L,
        mtimeEpochSeconds = 0L,
        mode = 0x1a4,
        linkTarget = null,
        rawPathBytes = cp437Bytes.toList(),
    )

    private fun plainEntry() = ArchiveTreeEntry(
        path = "plain.txt", ordinal = 3, kind = 0, flags = 0, uncompressedBytes = 5L,
        mtimeEpochSeconds = 0L, mode = 0x1a4, linkTarget = null,
    )

    @Test
    fun `an archive with no override defaults to AUTO`() {
        assertEquals(ArchiveNameEncoding.AUTO, ArchiveEncodingOverrides().encodingFor(archiveA))
    }

    @Test
    fun `setEncoding is remembered per archive, and AUTO clears it back to the default`() {
        val overrides = ArchiveEncodingOverrides()
        overrides.setEncoding(archiveA, ArchiveNameEncoding.CP866)
        assertEquals(ArchiveNameEncoding.CP866, overrides.encodingFor(archiveA))
        // A second archive is unaffected -- the map is keyed per archive, not global.
        assertEquals(ArchiveNameEncoding.AUTO, overrides.encodingFor(archiveB))
        overrides.setEncoding(archiveA, ArchiveNameEncoding.AUTO)
        assertEquals(ArchiveNameEncoding.AUTO, overrides.encodingFor(archiveA))
    }

    @Test
    fun `a non-lossy entry's display name is unaffected by any override`() {
        val overrides = ArchiveEncodingOverrides()
        val entry = plainEntry()
        assertEquals("plain.txt", overrides.displayNameFor(archiveA, entry))
        overrides.setEncoding(archiveA, ArchiveNameEncoding.SHIFT_JIS)
        assertEquals("plain.txt", overrides.displayNameFor(archiveA, entry))
    }

    @Test
    fun `a lossy entry re-decodes under the archive's chosen encoding, AUTO resolving through the detector`() {
        val overrides = ArchiveEncodingOverrides()
        val entry = lossyEntry()
        // AUTO (the default): the detector correctly reads this as CP-437.
        assertEquals("café.txt", overrides.displayNameFor(archiveA, entry))
        // An explicit, wrong choice still decodes to something else, never a crash or the lossy string.
        overrides.setEncoding(archiveA, ArchiveNameEncoding.CP866)
        val underWrongCharset = overrides.displayNameFor(archiveA, entry)
        assertNotEquals("café.txt", underWrongCharset)
        assertNotEquals("caf�.txt", underWrongCharset)
        // Back to CP-437 explicitly.
        overrides.setEncoding(archiveA, ArchiveNameEncoding.CP437)
        assertEquals("café.txt", overrides.displayNameFor(archiveA, entry))
    }

    @Test
    fun `changing the override never touches the entry's ordinal or path`() {
        val overrides = ArchiveEncodingOverrides()
        val entry = lossyEntry(ordinal = 42, path = "caf�.txt")
        val ordinalBefore = entry.ordinal
        val pathBefore = entry.path
        for (encoding in ArchiveNameEncoding.entries) {
            overrides.setEncoding(archiveA, encoding)
            overrides.displayNameFor(archiveA, entry)
            assertEquals(ordinalBefore, entry.ordinal)
            assertEquals(pathBefore, entry.path)
        }
    }

    @Test
    fun `a raw path with a directory separator is split the same way regardless of which separator a legacy tool used`() {
        val overrides = ArchiveEncodingOverrides()
        val forwardSlash = lossyEntry(path = "dir/caf�.txt", ordinal = 1).let {
            it.copy(rawPathBytes = (byteArrayOf(0x64, 0x69, 0x72, 0x2F) + cp437Bytes).toList())
        }
        assertEquals("café.txt", overrides.displayNameFor(archiveA, forwardSlash))
        val backslash = lossyEntry(path = "dir/caf�.txt", ordinal = 1).let {
            it.copy(rawPathBytes = (byteArrayOf(0x64, 0x69, 0x72, 0x5C) + cp437Bytes).toList())
        }
        assertEquals("café.txt", overrides.displayNameFor(archiveA, backslash))
    }
}
