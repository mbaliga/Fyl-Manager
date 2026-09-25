package io.github.mbaliga.fylz.archive

import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The listing file is not what the writer would have produced; the catalog rebuilds it. */
class ArchiveListingCorrupt(message: String) : IOException(message)

/**
 * One record of a listing as the decoder process wrote it (`fylz-archive/src/listing.rs`): the
 * entry's raw path exactly as the engine reported it (**not** normalised -- [ArchiveTree] does
 * that), its [ordinal] (the raw header index `extract_entry_at` walks to, never an index into a
 * list), and the metadata `ArchiveEntryInfo` carries with the same sentinels for unknowns.
 */
data class ArchiveListingRecord(
    val ordinal: Int,
    val path: String,
    val kind: Int,
    val flags: Int,
    /** [ArchiveEntryInfo.UNKNOWN_SIZE] when the header did not say. */
    val uncompressedBytes: Long,
    /** [ArchiveEntryInfo.UNKNOWN_MTIME] when the header did not say. */
    val mtimeEpochSeconds: Long,
    val mode: Int,
    val linkTarget: String?,
) {
    val isDirectory: Boolean get() = kind == ArchiveEntryInfo.KIND_DIRECTORY
    val isFile: Boolean get() = kind == ArchiveEntryInfo.KIND_FILE
    val encryptedData: Boolean get() = flags and ArchiveListingCodec.FLAG_ENCRYPTED_DATA != 0
    val encryptedMetadata: Boolean get() = flags and ArchiveListingCodec.FLAG_ENCRYPTED_METADATA != 0
    val nameLossy: Boolean get() = flags and ArchiveListingCodec.FLAG_NAME_LOSSY != 0
}

/** A decoded listing: every record in archive order, and whether the pass that wrote it stopped
 * on damage after these records ([partial]). */
class ArchiveListing(val records: List<ArchiveListingRecord>, val partial: Boolean)

/**
 * The reader half of the archive listing codec (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md`
 * section 2.2); the writer is Rust, and the two are held together by the golden `.fzl` files
 * under `app/src/test/resources/fixtures/archives/`, which the Rust writer must reproduce byte for
 * byte and this reader must decode (`ArchiveListingCodecTest`).
 *
 * Format, all integers little-endian:
 *
 * ```
 * magic     "FZL1"
 * record    tag 0x01 | ordinal u32 | path u32 len + bytes | kind u8 | flags u8 |
 *           uncompressed u64 | mtime i64 | mode u32 | [link target u32 len + bytes]
 * trailer   tag 0xFF | count u32 | partial u8
 * ```
 *
 * The file is **untrusted**: a compromised decoder process wrote it. Every length is checked
 * against what remains, strings are capped at [MAX_STRING_BYTES], a path may have at most
 * [MAX_PATH_DEPTH] segments, the record count may not exceed the caller's `maxEntries` (the same
 * `ArchiveLimits.maxListingEntries` the engine enforced) and must match the trailer's, and unknown
 * tags, kinds or flag bits, or a missing trailer, are [ArchiveListingCorrupt]. Nothing here
 * allocates in proportion to a claimed length before the bytes to back it are known to exist.
 */
object ArchiveListingCodec {
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'Z'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
    private const val TAG_RECORD = 0x01
    private const val TAG_TRAILER = 0xFF

    const val FLAG_ENCRYPTED_DATA = 0x01
    const val FLAG_ENCRYPTED_METADATA = 0x02
    const val FLAG_SIZE_UNKNOWN = 0x04
    const val FLAG_MTIME_UNKNOWN = 0x08
    const val FLAG_NAME_LOSSY = 0x10
    const val FLAG_HAS_LINK_TARGET = 0x20
    private const val KNOWN_FLAGS = FLAG_ENCRYPTED_DATA or FLAG_ENCRYPTED_METADATA or FLAG_SIZE_UNKNOWN or
        FLAG_MTIME_UNKNOWN or FLAG_NAME_LOSSY or FLAG_HAS_LINK_TARGET

    /** The longest path or link target a record may carry. */
    const val MAX_STRING_BYTES = 64 * 1024

    /** The most `/`-separated segments a raw path may have. */
    const val MAX_PATH_DEPTH = 1_024

    /** The fixed part of a record: tag, ordinal, path length, kind, flags, size, mtime, mode. */
    private const val RECORD_FIXED_BYTES = 1 + 4 + 4 + 1 + 1 + 8 + 8 + 4
    private const val TRAILER_BYTES = 1 + 4 + 1

    @Throws(ArchiveListingCorrupt::class, IOException::class)
    fun decode(file: File, maxEntries: Int): ArchiveListing = decode(file.readBytes(), maxEntries)

    @Throws(ArchiveListingCorrupt::class)
    fun decode(bytes: ByteArray, maxEntries: Int): ArchiveListing {
        if (bytes.size < MAGIC.size + TRAILER_BYTES) throw ArchiveListingCorrupt("listing too short (${bytes.size} bytes)")
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) throw ArchiveListingCorrupt("listing magic missing")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(MAGIC.size)
        val records = ArrayList<ArchiveListingRecord>()
        while (true) {
            if (buffer.remaining() < 1) throw ArchiveListingCorrupt("listing ends without a trailer")
            val tag = buffer.get().toInt() and 0xFF
            when (tag) {
                TAG_TRAILER -> {
                    if (buffer.remaining() != TRAILER_BYTES - 1) {
                        throw ArchiveListingCorrupt("listing trailer has ${buffer.remaining()} bytes, expected ${TRAILER_BYTES - 1}")
                    }
                    val count = buffer.int.toLong() and 0xFFFF_FFFFL
                    val partial = buffer.get().toInt() and 0xFF
                    if (count != records.size.toLong()) {
                        throw ArchiveListingCorrupt("listing trailer counts $count records, ${records.size} were present")
                    }
                    if (partial > 1) throw ArchiveListingCorrupt("listing trailer partial flag is $partial")
                    return ArchiveListing(records, partial == 1)
                }
                TAG_RECORD -> {
                    if (records.size >= maxEntries) {
                        throw ArchiveListingCorrupt("listing carries more than $maxEntries records")
                    }
                    if (buffer.remaining() < RECORD_FIXED_BYTES - 1) throw ArchiveListingCorrupt("listing record truncated")
                    val ordinal = buffer.int.toLong() and 0xFFFF_FFFFL
                    if (ordinal > Int.MAX_VALUE) throw ArchiveListingCorrupt("listing ordinal $ordinal out of range")
                    val path = readString(buffer, "path")
                    if (path.count { it == '/' } >= MAX_PATH_DEPTH) {
                        throw ArchiveListingCorrupt("listing path deeper than $MAX_PATH_DEPTH segments")
                    }
                    if (buffer.remaining() < 1 + 1 + 8 + 8 + 4) throw ArchiveListingCorrupt("listing record truncated after its path")
                    val kind = buffer.get().toInt() and 0xFF
                    if (kind > ArchiveEntryInfo.KIND_OTHER) throw ArchiveListingCorrupt("listing kind $kind unknown")
                    val flags = buffer.get().toInt() and 0xFF
                    if (flags and KNOWN_FLAGS.inv() != 0) throw ArchiveListingCorrupt("listing flags 0x${Integer.toHexString(flags)} unknown")
                    val uncompressed = buffer.long
                    val mtime = buffer.long
                    val mode = buffer.int
                    val linkTarget = if (flags and FLAG_HAS_LINK_TARGET != 0) readString(buffer, "link target") else null
                    records += ArchiveListingRecord(
                        ordinal = ordinal.toInt(),
                        path = path,
                        kind = kind,
                        flags = flags,
                        uncompressedBytes = if (flags and FLAG_SIZE_UNKNOWN != 0 || uncompressed < 0L) {
                            ArchiveEntryInfo.UNKNOWN_SIZE
                        } else {
                            uncompressed
                        },
                        mtimeEpochSeconds = if (flags and FLAG_MTIME_UNKNOWN != 0) ArchiveEntryInfo.UNKNOWN_MTIME else mtime,
                        mode = mode,
                        linkTarget = linkTarget,
                    )
                }
                else -> throw ArchiveListingCorrupt("listing tag 0x${Integer.toHexString(tag)} unknown")
            }
        }
    }

    private fun readString(buffer: ByteBuffer, what: String): String {
        if (buffer.remaining() < 4) throw ArchiveListingCorrupt("listing $what length truncated")
        val length = buffer.int.toLong() and 0xFFFF_FFFFL
        if (length > MAX_STRING_BYTES) throw ArchiveListingCorrupt("listing $what of $length bytes exceeds $MAX_STRING_BYTES")
        if (length > buffer.remaining()) throw ArchiveListingCorrupt("listing $what of $length bytes runs past the end")
        val start = buffer.position()
        buffer.position(start + length.toInt())
        return String(buffer.array(), buffer.arrayOffset() + start, length.toInt(), Charsets.UTF_8)
    }
}
