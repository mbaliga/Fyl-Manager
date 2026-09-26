package io.github.mbaliga.fylz.archive

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveExtractResult
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.ArchiveWriteOptions
import io.github.mbaliga.fylz.decoder.ArchiveWriteResult
import io.github.mbaliga.fylz.decoder.DecoderClient
import io.github.mbaliga.fylz.decoder.IDecoderService
import io.github.mbaliga.fylz.operations.OrdinalBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A test-only archive format and the `IDecoderService.Stub` that reads it, so the catalog, the
 * entry cache and the provider run end to end against a decoder that never loads native code
 * (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.10). A fake archive is
 *
 * ```
 * "FAKE" | u32 header length | header JSON | entry bodies
 * ```
 *
 * and the stub does what the real service does: reads the archive **through the descriptor it was
 * handed** (positioning that descriptor's own offset, as libarchive does), writes the listing in
 * the real codec into the sink **before returning** (Robolectric's file-backed pipes report EOF
 * before the first write, so the drain rule needs the bytes to be there), closes its copy of the
 * sink, and answers with the same Parcelables. [interleaveMillis] sleeps between body chunks so
 * two concurrent `extractEntry` calls on descriptors that share an offset would corrupt each other
 * -- the amendment-1 test. `extractRanges` (M3.4) writes the FZX1 frame stream the real engine
 * would for the bitmap's ordinals -- `BEGIN`/`DATA`/`END` per entry in ordinal order, directories
 * and links with their kinds and no data -- with injection points for a per-entry failure, an abort
 * at an ordinal, a truncation, a refusal and a lying size, and records every call's arguments.
 */
class FakeArchive(
    val entries: List<Entry>,
    val partial: Boolean = false,
    val policyAllowed: Boolean = true,
    val policyReason: String? = null,
    val structuralRefusal: String? = null,
    val formatCode: Int = ArchiveFormatFamily.ZIP,
) {
    class Entry(
        val path: String,
        val body: ByteArray = ByteArray(0),
        val kind: Int = ArchiveEntryInfo.KIND_FILE,
        val linkTarget: String? = null,
        val encrypted: Boolean = false,
        /** The size the listing declares; the body's length unless a test wants a lie. */
        val declaredSize: Long = body.size.toLong(),
        val mtime: Long = 1_577_836_800L,
        /** M3.7: `path` is always this entry's UTF-8 (possibly already lossy-looking, e.g. with a
         * literal replacement character) display string; a real lossy entry additionally sets this
         * so the fake decoder's listing carries a `FLAG_NAME_LOSSY` record with real raw bytes. */
        val nameLossy: Boolean = false,
        val rawPathBytes: ByteArray? = null,
    )

    fun write(file: File): File {
        file.parentFile?.mkdirs()
        val bodies = ByteArrayOutputStream()
        val offsets = entries.map { entry -> bodies.size().also { bodies.write(entry.body) } }
        val json = JSONObject()
            .put("partial", partial)
            .put("policyAllowed", policyAllowed)
            .put("policyReason", policyReason ?: JSONObject.NULL)
            .put("structuralRefusal", structuralRefusal ?: JSONObject.NULL)
            .put("formatCode", formatCode)
            .put(
                "entries",
                JSONArray().also { array ->
                    entries.forEachIndexed { index, entry ->
                        array.put(
                            JSONObject()
                                .put("o", index)
                                .put("p", entry.path)
                                .put("k", entry.kind)
                                .put("s", entry.declaredSize)
                                .put("len", entry.body.size)
                                .put("off", offsets[index])
                                .put("lt", entry.linkTarget ?: JSONObject.NULL)
                                .put("enc", entry.encrypted)
                                .put("mt", entry.mtime)
                                .put("nl", entry.nameLossy)
                                .put("rpb", entry.rawPathBytes?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL),
                        )
                    }
                },
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("FAKE".toByteArray()).putInt(json.size).array()
        file.writeBytes(header + json + bodies.toByteArray())
        return file
    }

    companion object {
        const val MAGIC = "FAKE"

        /** The bytes of a nested fake archive, usable as an entry body. */
        fun bytes(archive: FakeArchive): ByteArray {
            val temp = File.createTempFile("fake", ".bin")
            try {
                return archive.write(temp).readBytes()
            } finally {
                temp.delete()
            }
        }
    }
}

/** What the stub read from a fake archive's header. */
private class FakeHeader(val json: JSONObject, val bodyBase: Int) {
    val entries: JSONArray = json.getJSONArray("entries")
}

class FakeArchiveDecoder(
    /** Sleep between body chunks, so concurrent extractions interleave (0 = none). */
    private val interleaveMillis: Long = 0L,
    private val chunkBytes: Int = 7,
) : IDecoderService.Stub() {
    val listCalls = AtomicInteger()
    val extractCalls = AtomicInteger()

    /** When set, `listArchive` answers with this failed inspection instead of reading the archive. */
    var listFailure: ArchiveInspection? = null

    /** When set, `extractEntry` answers with this failure instead of streaming. */
    var extractFailure: ArchiveExtractResult? = null

    /** When set, `extractEntry` writes this many bytes fewer than it reports (a lying service). */
    var shortByBytes: Int = 0

    /** How long `listArchive` sleeps before writing anything (a slow decoder). */
    var listDelayMillis: Long = 0L

    // ------------------------------------------------------------------ M3.4: extractRanges

    /** One `extractRanges` call as the stub saw it. */
    class RangesCall(val archiveFd: Int, val limits: ArchiveLimits, val ordinals: OrdinalBitmap)

    val rangesCalls = CopyOnWriteArrayList<RangesCall>()

    /** Ordinals whose entry fails with a `FAIL` frame of this kind after its data (a CRC/size/decode failure). */
    val failOrdinals = HashMap<Int, Int>()

    /** Ordinals that fail with a stand-alone `FAIL` (a header-level failure): no `BEGIN`, no data. */
    val headerFailOrdinals = HashSet<Int>()

    /** When set, the stream aborts (`ABORT`, result CORRUPT with `stopOrdinal`) on reaching this ordinal -- **once**; the next call runs through. */
    var abortAtOrdinal: Int? = null

    /** When set, the stream is cut (no terminal frame, result OK) after this many bytes of the first entry at or after `truncateAtOrdinal`. */
    var truncateAtOrdinal: Int? = null

    /** When set, `extractRanges` answers this result and writes no frame at all. */
    var rangesFailure: ArchiveExtractResult? = null

    /** Ordinals whose `END.bytes` lies by this much (a size mismatch the reader must catch). */
    val lieAboutSize = HashMap<Int, Long>()

    /** Sleep between `DATA` frames, so a cancel can land mid-entry. */
    var rangesInterleaveMillis: Long = 0L

    /** Called before each `DATA` frame is written; returning false stops the stream where it is (a cancel seen by the engine). */
    var beforeData: (ordinal: Int) -> Boolean = { true }

    override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult {
        val bitmap = OrdinalBitmap.fromByteArray(ordinalsBitmap)
        rangesCalls += RangesCall(archive.fd, limits, bitmap)
        rangesFailure?.let { failure ->
            sink.close()
            return failure
        }
        val header = readHeader(archive)
        val channel = FileInputStream(archive.fileDescriptor).channel
        var entriesWritten = 0
        var bytesWritten = 0L
        var entriesFailed = 0
        var stop: Int? = null
        var outcome = ArchiveExtractResult.OUTCOME_OK
        var message: String? = null
        ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
            val writer = ExtractFrameTestWriter(out)
            try {
                run loop@{
                    bitmap.ordinals().forEach { ordinal ->
                        if (ordinal >= header.entries.length()) return@forEach
                        // Read afresh per entry, so a test can arm the abort from `beforeData` mid-call.
                        val abortAt = abortAtOrdinal
                        if (abortAt != null && ordinal >= abortAt) {
                            abortAtOrdinal = null
                            writer.abort("Damaged archive at $ordinal")
                            outcome = ArchiveExtractResult.OUTCOME_CORRUPT
                            message = "Damaged archive at $ordinal"
                            stop = ordinal
                            return@loop
                        }
                        val entry = header.entries.getJSONObject(ordinal)
                        val kind = entry.getInt("k")
                        val path = entry.getString("p")
                        if (ordinal in headerFailOrdinals) {
                            writer.fail(ordinal, ExtractFrameReader.FAIL_DECODE, "header unreadable")
                            entriesFailed += 1
                            return@forEach
                        }
                        val declared = if (kind == ArchiveEntryInfo.KIND_FILE) entry.getLong("s") else 0L
                        writer.begin(ordinal, declared, frameKind(kind), path)
                        if (kind != ArchiveEntryInfo.KIND_FILE) {
                            writer.end(ordinal, 0L, null)
                            entriesWritten += 1
                            return@forEach
                        }
                        val length = entry.getInt("len")
                        val offset = header.bodyBase + entry.getInt("off")
                        var at = 0
                        val truncateAt = truncateAtOrdinal
                        while (at < length) {
                            val n = minOf(chunkBytes, length - at)
                            if (!beforeData(ordinal)) {
                                outcome = ArchiveExtractResult.OUTCOME_CANCELLED
                                message = "cancelled"
                                stop = ordinal
                                return@loop
                            }
                            channel.position((offset + at).toLong())
                            if (rangesInterleaveMillis > 0) Thread.sleep(rangesInterleaveMillis)
                            val buffer = ByteBuffer.allocate(n)
                            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) break
                            if (truncateAt != null && ordinal >= truncateAt && at + n >= minOf(length, chunkBytes * 2)) {
                                // Cut mid-entry: what a killed engine leaves. The result still says OK.
                                truncateAtOrdinal = null
                                writer.data(ordinal, buffer.array().copyOf(maxOf(1, buffer.position() / 2)))
                                return@loop
                            }
                            writer.data(ordinal, buffer.array().copyOf(buffer.position()))
                            at += n
                        }
                        val failKind = failOrdinals[ordinal]
                        if (failKind != null) {
                            writer.fail(ordinal, failKind, "entry $ordinal failed")
                            entriesFailed += 1
                        } else {
                            writer.end(ordinal, length.toLong() + (lieAboutSize[ordinal] ?: 0L), null)
                            entriesWritten += 1
                            bytesWritten += length
                        }
                    }
                    writer.done(entriesWritten, bytesWritten, entriesFailed)
                }
            } catch (broken: IOException) {
                // The reader went away (a cancel closed the read end): the engine reports Cancelled.
                outcome = ArchiveExtractResult.OUTCOME_CANCELLED
                message = "cancelled"
            }
        }
        return ArchiveExtractResult(outcome, message, bytesWritten, entriesWritten, entriesFailed, stop ?: ArchiveExtractResult.NO_STOP_ORDINAL)
    }

    override fun ping(): Boolean = true

    override fun sniff(pfd: ParcelFileDescriptor): String = "application/octet-stream"

    override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection =
        summary(readHeader(archive), rows = true, maxRows = maxRows)

    override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection {
        listCalls.incrementAndGet()
        if (listDelayMillis > 0) Thread.sleep(listDelayMillis)
        listFailure?.let { failure ->
            sink.close()
            return failure
        }
        val header = readHeader(archive)
        val records = List(header.entries.length()) { index ->
            val entry = header.entries.getJSONObject(index)
            ArchiveListingTestWriter.record(
                ordinal = entry.getInt("o"),
                path = entry.getString("p"),
                kind = entry.getInt("k"),
                uncompressedBytes = entry.getLong("s"),
                mtimeEpochSeconds = entry.getLong("mt"),
                linkTarget = entry.optString("lt").takeUnless { entry.isNull("lt") },
                encryptedData = entry.getBoolean("enc"),
                nameLossy = entry.optBoolean("nl", false),
                rawPathBytes = entry.optString("rpb").takeUnless { entry.isNull("rpb") }?.let { Base64.getDecoder().decode(it) },
            )
        }
        ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
            out.write(ArchiveListingTestWriter.encode(records, partial = header.json.getBoolean("partial")))
        }
        return summary(header, rows = false, maxRows = 0)
    }

    override fun extractEntry(
        archive: ParcelFileDescriptor,
        ordinal: Int,
        expectedPath: String,
        limits: ArchiveLimits,
        sink: ParcelFileDescriptor,
    ): ArchiveExtractResult {
        extractCalls.incrementAndGet()
        extractFailure?.let { failure ->
            sink.close()
            return failure
        }
        val header = readHeader(archive)
        if (ordinal < 0 || ordinal >= header.entries.length()) {
            sink.close()
            return ArchiveExtractResult.failed(ArchiveExtractResult.OUTCOME_NOT_FOUND, "no entry \"$expectedPath\" at header $ordinal")
        }
        val entry = header.entries.getJSONObject(ordinal)
        if (entry.getString("p") != expectedPath) {
            sink.close()
            return ArchiveExtractResult.failed(ArchiveExtractResult.OUTCOME_NOT_FOUND, "no entry \"$expectedPath\" at header $ordinal")
        }
        val length = entry.getInt("len")
        val offset = header.bodyBase + entry.getInt("off")
        var written = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
            // Read through the descriptor we were handed, positioning ITS offset before every chunk
            // -- exactly what a shared open file description would let another call disturb.
            val channel = FileInputStream(archive.fileDescriptor).channel
            var at = 0
            val toWrite = maxOf(0, length - shortByBytes)
            while (at < toWrite) {
                val n = minOf(chunkBytes, toWrite - at)
                channel.position((offset + at).toLong())
                if (interleaveMillis > 0) Thread.sleep(interleaveMillis)
                val buffer = ByteBuffer.allocate(n)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break
                }
                out.write(buffer.array(), 0, buffer.position())
                written += buffer.position()
                at += n
            }
        }
        return ArchiveExtractResult.ok(written + shortByBytes)
    }

    // ------------------------------------------------------------------ M3.5: writeArchive

    /** One `writeArchive` call as the stub saw it. */
    class WriteCall(val options: ArchiveWriteOptions)

    val writeCalls = CopyOnWriteArrayList<WriteCall>()

    /** The most recent `writeArchive` call's own result, for a test to inspect a failure's exact message. */
    @Volatile
    var lastWriteResult: ArchiveWriteResult? = null

    /** When set, `writeArchive` answers this failure instead of reading any frame -- a decoder
     * that refuses outright (the real engine's `OUTCOME_NOT_SEEKABLE`/`OUTCOME_UNSUPPORTED`). */
    var writeFailure: ArchiveWriteResult? = null

    /** Called before each `DATA` frame is applied; returning false stops the read where it is, as
     * the real engine sees when the app cancels by closing its end of the sink pipe. */
    var beforeWriteData: (ordinal: Int) -> Boolean = { true }

    /** The `ENTRY` at this ordinal answers a `PROTOCOL_ERROR` instead of being accepted -- a
     * malformed frame the feeder should never send. */
    var protocolErrorAtOrdinal: Int? = null

    /**
     * Reads the `FZW1` frame stream [ArchiveFrameWriter] writes and, on `FINISH`, encodes what it
     * saw as a fake archive [output] can itself be read back through (`listArchive`/
     * `inspectArchive`/`extractRanges` above) -- so a create pass's own secondary verification
     * (design section 2.6) runs end to end against this stub too. `ABORT`, a protocol violation or
     * [beforeWriteData] returning false all close [output] having written nothing, mirroring the
     * real engine's poisoning: a truncated result must not parse as a valid archive.
     */
    override fun writeArchive(
        input: ParcelFileDescriptor,
        options: ArchiveWriteOptions,
        output: ParcelFileDescriptor,
    ): ArchiveWriteResult {
        writeCalls += WriteCall(options)
        writeFailure?.let { failure ->
            input.close()
            output.close()
            return failure
        }
        val entries = ArrayList<FakeArchive.Entry>()
        var bytesIn = 0L
        var openOrdinal: Int? = null
        var openPath = ""
        var openBody: ByteArrayOutputStream? = null
        var cancelled = false
        var protocolError: String? = null
        var abortMessage: String? = null
        var finished = false
        ParcelFileDescriptor.AutoCloseInputStream(input).use { rawIn ->
            val magic = readFully(rawIn, 4)
            if (magic == null || String(magic, Charsets.US_ASCII) != "FZW1") {
                protocolError = "missing FZW1 magic"
            } else {
                run loop@{
                    while (true) {
                        val tag = pollingReadByte(rawIn)
                        if (tag < 0) {
                            protocolError = "input ended before FINISH or ABORT"
                            return@loop
                        }
                        when (tag) {
                            ArchiveFrameWriter.TAG_ENTRY -> {
                                if (openOrdinal != null) {
                                    protocolError = "ENTRY while an entry is still open"
                                    return@loop
                                }
                                val header = readFully(rawIn, 4 + 1 + 8 + 8 + 4 + 4) ?: run { protocolError = "short ENTRY header"; return@loop }
                                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                                val ordinal = buffer.int
                                val kind = buffer.get().toInt()
                                buffer.long // size: read at feed time by the real engine too, not trusted here
                                buffer.long // mtime, unused by this fake
                                buffer.int // mode, unused by this fake
                                val pathLen = buffer.int
                                val pathBytes = readFully(rawIn, pathLen) ?: run { protocolError = "short ENTRY path"; return@loop }
                                val path = String(pathBytes, Charsets.UTF_8)
                                if (protocolErrorAtOrdinal == ordinal) {
                                    protocolError = "injected protocol error at ordinal $ordinal"
                                    return@loop
                                }
                                if (kind == ArchiveFrameWriter.KIND_DIRECTORY) {
                                    entries += FakeArchive.Entry(path = path, kind = ArchiveEntryInfo.KIND_DIRECTORY, declaredSize = 0L)
                                } else {
                                    openOrdinal = ordinal
                                    openPath = path
                                    openBody = ByteArrayOutputStream()
                                }
                            }
                            ArchiveFrameWriter.TAG_DATA -> {
                                val header = readFully(rawIn, 4 + 4) ?: run { protocolError = "short DATA header"; return@loop }
                                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                                val ordinal = buffer.int
                                val length = buffer.int
                                val chunk = readFully(rawIn, length) ?: run { protocolError = "short DATA body"; return@loop }
                                if (openOrdinal != ordinal) {
                                    protocolError = "DATA for ordinal $ordinal with no matching open entry"
                                    return@loop
                                }
                                if (!beforeWriteData(ordinal)) {
                                    cancelled = true
                                    return@loop
                                }
                                openBody!!.write(chunk)
                                bytesIn += chunk.size
                            }
                            ArchiveFrameWriter.TAG_END -> {
                                val header = readFully(rawIn, 4 + 8) ?: run { protocolError = "short END header"; return@loop }
                                val ordinal = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                                if (openOrdinal != ordinal) {
                                    protocolError = "END for ordinal $ordinal with no matching open entry"
                                    return@loop
                                }
                                entries += FakeArchive.Entry(path = openPath, body = openBody!!.toByteArray(), kind = ArchiveEntryInfo.KIND_FILE)
                                openOrdinal = null
                                openBody = null
                            }
                            ArchiveFrameWriter.TAG_FINISH -> {
                                if (openOrdinal != null) {
                                    protocolError = "FINISH while an entry is still open"
                                } else {
                                    finished = true
                                }
                                return@loop
                            }
                            ArchiveFrameWriter.TAG_ABORT -> {
                                if (openOrdinal != null) {
                                    protocolError = "ABORT while an entry is still open"
                                    return@loop
                                }
                                val lengthBytes = readFully(rawIn, 2) ?: run { protocolError = "short ABORT length"; return@loop }
                                val messageLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                                val messageBytes = readFully(rawIn, messageLength) ?: run { protocolError = "short ABORT message"; return@loop }
                                abortMessage = String(messageBytes, Charsets.UTF_8)
                                return@loop
                            }
                            else -> {
                                protocolError = "unknown frame tag $tag"
                                return@loop
                            }
                        }
                    }
                }
            }
        }
        val outcome = when {
            protocolError != null -> {
                output.close()
                ArchiveWriteResult.failed(ArchiveWriteResult.OUTCOME_PROTOCOL_ERROR, protocolError)
            }
            cancelled -> {
                output.close()
                ArchiveWriteResult.failed(ArchiveWriteResult.OUTCOME_CANCELLED, "cancelled")
            }
            abortMessage != null -> {
                output.close()
                ArchiveWriteResult.failed(ArchiveWriteResult.OUTCOME_CANCELLED, abortMessage)
            }
            finished -> {
                val bytes = FakeArchive.bytes(FakeArchive(entries))
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(bytes) }
                ArchiveWriteResult.ok(entries.size, bytesIn, bytes.size.toLong())
            }
            else -> {
                output.close()
                ArchiveWriteResult.failed(ArchiveWriteResult.OUTCOME_PROTOCOL_ERROR, "stream ended without FINISH or ABORT")
            }
        }
        lastWriteResult = outcome
        return outcome
    }

    /** Reads exactly [length] bytes, or null on a genuine EOF before that many arrived (a
     * short/killed feeder) -- through [pollingRead], never a raw [InputStream.read]. */
    private fun readFully(input: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = pollingRead(input, buffer, at, length - at)
            if (n < 0) return null
            at += n
        }
        return buffer
    }

    /**
     * [InputStream.read] retried across a real, confirmed Robolectric limitation: its
     * [ParcelFileDescriptor] pipes report EOF for "nothing written yet" exactly as readily as for
     * "the writer actually closed its end" -- a real OS pipe never does the former while the write
     * end stays open (`PipeDiagnosticTest` proves this against a plain pipe with no frame codec
     * involved at all: a read that returns -1 while a second thread is about to write succeeds on
     * a retry once that write actually happens). Without this, `writeArchive` below -- run
     * concurrently against the real feeder through `DecoderClient.callTwoPipes`, exactly as
     * production code drives it -- sees a spurious `PROTOCOL_ERROR` the instant it is scheduled
     * even slightly ahead of the feeder, on every call, deterministically. Retries for up to
     * [POLL_TIMEOUT_MILLIS] before answering a genuine end of stream.
     */
    private fun pollingRead(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        val deadlineNanos = System.nanoTime() + POLL_TIMEOUT_MILLIS * 1_000_000L
        while (true) {
            val n = input.read(buffer, offset, length)
            if (n >= 0) return n
            if (System.nanoTime() >= deadlineNanos) return -1
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private fun pollingReadByte(input: InputStream): Int {
        val single = ByteArray(1)
        val n = pollingRead(input, single, 0, 1)
        return if (n < 0) -1 else single[0].toInt() and 0xFF
    }

    /** The listing's `ArchiveEntryInfo.KIND_*` to the frame codec's kind codes (`frames.rs`'s `kind_code`). */
    private fun frameKind(kind: Int): Int = when (kind) {
        ArchiveEntryInfo.KIND_FILE -> ExtractFrameReader.KIND_FILE
        ArchiveEntryInfo.KIND_DIRECTORY -> ExtractFrameReader.KIND_DIRECTORY
        ArchiveEntryInfo.KIND_SYMLINK -> ExtractFrameReader.KIND_SYMLINK
        ArchiveEntryInfo.KIND_HARDLINK -> ExtractFrameReader.KIND_HARDLINK
        else -> ExtractFrameReader.KIND_OTHER
    }

    private fun readHeader(archive: ParcelFileDescriptor): FakeHeader {
        val channel = FileInputStream(archive.fileDescriptor).channel
        channel.position(0L)
        val fixed = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (fixed.hasRemaining()) check(channel.read(fixed) >= 0) { "short fake archive header" }
        fixed.flip()
        val magic = ByteArray(4).also { fixed.get(it) }
        check(String(magic) == FakeArchive.MAGIC) { "not a fake archive: ${String(magic)}" }
        val jsonLength = fixed.int
        val json = ByteBuffer.allocate(jsonLength)
        while (json.hasRemaining()) check(channel.read(json) >= 0) { "short fake archive header" }
        return FakeHeader(JSONObject(String(json.array(), Charsets.UTF_8)), 8 + jsonLength)
    }

    private fun summary(header: FakeHeader, rows: Boolean, maxRows: Int): ArchiveInspection {
        val entries = header.entries
        var files = 0
        var dirs = 0
        var links = 0
        var total = 0L
        var encrypted = false
        val rowList = ArrayList<ArchiveEntryInfo>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.getBoolean("enc")) encrypted = true
            when (entry.getInt("k")) {
                ArchiveEntryInfo.KIND_FILE -> files++
                ArchiveEntryInfo.KIND_DIRECTORY -> dirs++
                ArchiveEntryInfo.KIND_SYMLINK, ArchiveEntryInfo.KIND_HARDLINK -> links++
            }
            if (entry.getInt("k") != ArchiveEntryInfo.KIND_DIRECTORY) total += entry.getLong("s")
            if (rows && rowList.size < maxRows) {
                rowList += ArchiveEntryInfo(
                    path = entry.getString("p"), kind = entry.getInt("k"),
                    linkTarget = entry.optString("lt").takeUnless { entry.isNull("lt") },
                    uncompressedBytes = entry.getLong("s"), mtimeEpochSeconds = entry.getLong("mt"), mode = 0x1a4,
                    encryptedData = entry.getBoolean("enc"), encryptedMetadata = false, nameLossy = false, ordinal = entry.getInt("o"),
                )
            }
        }
        return ArchiveInspection(
            outcome = ArchiveInspection.OUTCOME_OK,
            message = null,
            formatCode = header.json.getInt("formatCode"),
            formatName = "fake",
            filters = emptyList(),
            archiveBytes = 0L,
            entryCount = entries.length(),
            fileCount = files,
            directoryCount = dirs,
            linkCount = links,
            totalUncompressedBytes = total,
            hasEncryptedEntries = encrypted,
            hasEncryptedMetadata = false,
            hasLossyNames = false,
            policyAllowed = header.json.getBoolean("policyAllowed"),
            policyReason = header.json.optString("policyReason").takeUnless { header.json.isNull("policyReason") },
            rows = rowList,
            rowsTruncated = rows && entries.length() > maxRows,
            partial = header.json.getBoolean("partial"),
            partialMessage = if (header.json.getBoolean("partial")) "Damaged tar archive" else null,
            structuralRefusal = header.json.optString("structuralRefusal").takeUnless { header.json.isNull("structuralRefusal") },
        )
    }

    companion object {
        val componentName = ComponentName("io.github.mbaliga.fylz", "io.github.mbaliga.fylz.decoder.DecoderService")

        /** [pollingRead]'s own retry budget: generous against real feeder work (a large entry's
         * many chunks), still bounded so a truly stuck test fails in seconds, not hangs forever. */
        private const val POLL_TIMEOUT_MILLIS = 5_000L
        private const val POLL_INTERVAL_MILLIS = 5L

        /** A [DecoderClient] bound to [stub] through the test seam, with short budgets. */
        fun client(
            stub: IDecoderService.Stub,
            idleUnbindMillis: Long = DecoderClient.IDLE_UNBIND_MILLIS,
            onUnbind: () -> Unit = {},
            bindCounter: AtomicInteger = AtomicInteger(),
        ): DecoderClient = DecoderClient(
            bind = { connection -> bindCounter.incrementAndGet(); connection.onServiceConnected(componentName, stub); true },
            unbind = { onUnbind() },
            idleUnbindMillis = idleUnbindMillis,
            livenessPollMillis = 20L,
        )
    }
}
