package io.github.mbaliga.fylz.archive

import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Kotlin encoder for the listing codec, **test-only**: production listings are written by the
 * Rust engine (`fylz-archive/src/listing.rs`), and the golden `.fzl` files pin the two halves
 * together. Fakes of `IDecoderService.Stub` use this to write a known listing into the sink they
 * are handed, and the bounds tests build hostile listings with it (or by hand-editing its output).
 */
object ArchiveListingTestWriter {
    fun record(
        ordinal: Int,
        path: String,
        kind: Int = ArchiveEntryInfo.KIND_FILE,
        uncompressedBytes: Long = 0L,
        mtimeEpochSeconds: Long = 1_577_836_800L,
        mode: Int = 0x1a4,
        linkTarget: String? = null,
        encryptedData: Boolean = false,
        encryptedMetadata: Boolean = false,
        nameLossy: Boolean = false,
    ): ArchiveListingRecord {
        var flags = 0
        if (encryptedData) flags = flags or ArchiveListingCodec.FLAG_ENCRYPTED_DATA
        if (encryptedMetadata) flags = flags or ArchiveListingCodec.FLAG_ENCRYPTED_METADATA
        if (uncompressedBytes == ArchiveEntryInfo.UNKNOWN_SIZE) flags = flags or ArchiveListingCodec.FLAG_SIZE_UNKNOWN
        if (mtimeEpochSeconds == ArchiveEntryInfo.UNKNOWN_MTIME) flags = flags or ArchiveListingCodec.FLAG_MTIME_UNKNOWN
        if (nameLossy) flags = flags or ArchiveListingCodec.FLAG_NAME_LOSSY
        if (linkTarget != null) flags = flags or ArchiveListingCodec.FLAG_HAS_LINK_TARGET
        return ArchiveListingRecord(ordinal, path, kind, flags, uncompressedBytes, mtimeEpochSeconds, mode, linkTarget)
    }

    fun directory(ordinal: Int, path: String): ArchiveListingRecord =
        record(ordinal, path, kind = ArchiveEntryInfo.KIND_DIRECTORY, mode = 0x1ed)

    /** Encodes [records] with a trailer; [trailerCount] and [partial] default to the truthful values. */
    fun encode(
        records: List<ArchiveListingRecord>,
        partial: Boolean = false,
        trailerCount: Int = records.size,
        withTrailer: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("FZL1".toByteArray(Charsets.US_ASCII))
        records.forEach { record -> out.write(encodeRecord(record)) }
        if (withTrailer) {
            out.write(0xFF)
            out.write(le32(trailerCount))
            out.write(if (partial) 1 else 0)
        }
        return out.toByteArray()
    }

    fun encodeRecord(record: ArchiveListingRecord): ByteArray {
        val path = record.path.toByteArray(Charsets.UTF_8)
        val target = record.linkTarget?.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 4 + 4 + path.size + 1 + 1 + 8 + 8 + 4 + (target?.let { 4 + it.size } ?: 0))
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x01)
        buffer.putInt(record.ordinal)
        buffer.putInt(path.size)
        buffer.put(path)
        buffer.put(record.kind.toByte())
        buffer.put(record.flags.toByte())
        buffer.putLong(if (record.uncompressedBytes == ArchiveEntryInfo.UNKNOWN_SIZE) 0L else record.uncompressedBytes)
        buffer.putLong(if (record.mtimeEpochSeconds == ArchiveEntryInfo.UNKNOWN_MTIME) 0L else record.mtimeEpochSeconds)
        buffer.putInt(record.mode)
        if (target != null) {
            buffer.putInt(target.size)
            buffer.put(target)
        }
        return buffer.array()
    }

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
