package io.github.mbaliga.fylz.archive

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The stream broke a rule of the codec: the call is abandoned and the operation fails `PROTOCOL_ERROR`. */
class ExtractProtocolException(message: String) : IOException(message)

/** How an extraction stream ended (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.3 step 4). */
sealed interface ExtractStreamEnd {
    /** `DONE`: the engine's pass ran to its end. */
    data class Done(val entries: Int, val bytes: Long, val failed: Int) : ExtractStreamEnd

    /** `ABORT`: the engine went fatal; completed entries stand, the open entry is lost. */
    data class Aborted(val message: String) : ExtractStreamEnd

    /** EOF without a terminal frame, or mid-frame: handled like [Aborted]. */
    data object TransportLoss : ExtractStreamEnd

    /** Not a single byte: what a refusal, an unsupported input or a cancel before the first frame leave. */
    data object Empty : ExtractStreamEnd
}

/** What the reader hands the demultiplexer, frame by frame, in order. Any exception thrown here ends the read. */
interface ExtractFrameSink {
    fun begin(ordinal: Int, declaredBytes: Long, kind: Int, rawPath: ByteArray)

    /** One slice of the open entry's data; [buffer] is reused after the call returns. */
    fun data(ordinal: Int, buffer: ByteArray, offset: Int, length: Int)

    fun end(ordinal: Int, bytes: Long, warning: String?)

    fun fail(ordinal: Int, kind: Int, message: String)
}

/**
 * The reader half of the extraction frame codec (`fylz-ffi-android/src/frames.rs` is the writer;
 * the golden `tree.fzx` holds the two together). The stream is **untrusted** -- a compromised
 * decoder process wrote it -- so every bound the design lists is checked before a byte is acted
 * on: the magic first (unless the stream is empty, which is what a refused or cancelled call
 * leaves and the result is the authority for), ordinals must be in the plan and strictly
 * increasing, one `BEGIN` per ordinal, `DATA` only between an entry's `BEGIN` and its `END`/`FAIL`,
 * a `DATA` frame carries 1 to [MAX_DATA_FRAME_BYTES] bytes, an entry's cumulative bytes stay within
 * its declared size when known and the plan's total, a path is at most [MAX_PATH_BYTES], the kind
 * and failure codes are the codec's, nothing follows `DONE`/`ABORT`. A violation is
 * [ExtractProtocolException]; EOF without a terminal frame, or mid-frame, is
 * [ExtractStreamEnd.TransportLoss]. Nothing here allocates in proportion to a claimed length before
 * the bytes to back it are read.
 */
class ExtractFrameReader(
    private val isPlanned: (Int) -> Boolean,
    /** The plan's total expected bytes; cumulative data past it is a protocol error. */
    private val planTotalBytes: Long,
) {
    private var lastOrdinal = -1
    private var open: Int? = null
    private var openDeclared = DECLARED_UNKNOWN
    private var openBytes = 0L
    private var cumulative = 0L

    /**
     * Reads frames from [input] until a terminal frame or EOF, handing each to [sink]. Throws
     * [ExtractProtocolException] on a codec violation, and whatever [sink] throws; an EOF inside a
     * frame is [ExtractStreamEnd.TransportLoss], never an exception.
     */
    @Throws(ExtractProtocolException::class, IOException::class)
    fun read(input: InputStream, sink: ExtractFrameSink): ExtractStreamEnd = try {
        readFrames(input, sink)
    } catch (cut: StreamCut) {
        ExtractStreamEnd.TransportLoss
    }

    private fun readFrames(input: InputStream, sink: ExtractFrameSink): ExtractStreamEnd {
        val magic = ByteArray(4)
        when (val got = readUpTo(input, magic, 4)) {
            0 -> return ExtractStreamEnd.Empty
            4 -> if (!magic.contentEquals(MAGIC)) throw ExtractProtocolException("extraction stream magic missing")
            else -> throw ExtractProtocolException("extraction stream cut inside its magic ($got bytes)")
        }
        val header = ByteArray(HEADER_BYTES)
        val little = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val payload = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val tag = input.read()
            if (tag < 0) return ExtractStreamEnd.TransportLoss
            when (tag) {
                TAG_BEGIN -> {
                    readExactly(input, header, 4 + 8 + 1 + 4)
                    little.position(0)
                    val ordinal = little.int
                    val declared = little.long
                    val kind = little.get().toInt() and 0xFF
                    val pathLength = little.int.toLong() and 0xFFFF_FFFFL
                    if (open != null) throw ExtractProtocolException("BEGIN $ordinal while $open is open")
                    checkOrdinal(ordinal)
                    if (declared < DECLARED_UNKNOWN) throw ExtractProtocolException("BEGIN $ordinal declares $declared bytes")
                    if (kind !in KIND_FILE..KIND_OTHER) throw ExtractProtocolException("BEGIN $ordinal kind $kind unknown")
                    if (pathLength > MAX_PATH_BYTES) throw ExtractProtocolException("BEGIN $ordinal path of $pathLength bytes exceeds $MAX_PATH_BYTES")
                    val rawPath = ByteArray(pathLength.toInt())
                    readExactly(input, rawPath, rawPath.size)
                    lastOrdinal = ordinal
                    open = ordinal
                    openDeclared = declared
                    openBytes = 0L
                    sink.begin(ordinal, declared, kind, rawPath)
                }
                TAG_DATA -> {
                    readExactly(input, header, 4 + 4)
                    little.position(0)
                    val ordinal = little.int
                    val length = little.int.toLong() and 0xFFFF_FFFFL
                    if (open != ordinal) throw ExtractProtocolException("DATA for $ordinal outside its entry (open: $open)")
                    if (length < 1 || length > MAX_DATA_FRAME_BYTES) throw ExtractProtocolException("DATA $ordinal of $length bytes")
                    if (openDeclared != DECLARED_UNKNOWN && openBytes + length > openDeclared) {
                        throw ExtractProtocolException("DATA $ordinal past its declared $openDeclared bytes")
                    }
                    if (cumulative + length > planTotalBytes) throw ExtractProtocolException("DATA $ordinal past the plan's $planTotalBytes bytes")
                    var remaining = length.toInt()
                    while (remaining > 0) {
                        val chunk = minOf(remaining, payload.size)
                        readExactly(input, payload, chunk)
                        sink.data(ordinal, payload, 0, chunk)
                        remaining -= chunk
                    }
                    openBytes += length
                    cumulative += length
                }
                TAG_END -> {
                    readExactly(input, header, 4 + 8 + 1 + 2)
                    little.position(0)
                    val ordinal = little.int
                    val bytes = little.long
                    val warn = little.get().toInt() and 0xFF
                    val messageLength = little.short.toInt() and 0xFFFF
                    if (open != ordinal) throw ExtractProtocolException("END for $ordinal outside its entry (open: $open)")
                    if (warn > WARN_OTHER) throw ExtractProtocolException("END $ordinal warn $warn unknown")
                    val message = readMessage(input, messageLength)
                    open = null
                    sink.end(ordinal, bytes, if (warn == WARN_OTHER) message else null)
                }
                TAG_FAIL -> {
                    readExactly(input, header, 4 + 1 + 2)
                    little.position(0)
                    val ordinal = little.int
                    val kind = little.get().toInt() and 0xFF
                    val messageLength = little.short.toInt() and 0xFFFF
                    if (kind > FAIL_DECODE) throw ExtractProtocolException("FAIL $ordinal kind $kind unknown")
                    val currentlyOpen = open
                    if (currentlyOpen == null) {
                        // A header-level failure: stands alone, still one ordinal, still in order.
                        checkOrdinal(ordinal)
                        lastOrdinal = ordinal
                    } else if (currentlyOpen != ordinal) {
                        throw ExtractProtocolException("FAIL for $ordinal while $currentlyOpen is open")
                    }
                    val message = readMessage(input, messageLength)
                    open = null
                    sink.fail(ordinal, kind, message)
                }
                TAG_DONE -> {
                    readExactly(input, header, 4 + 8 + 4)
                    little.position(0)
                    val entries = little.int
                    val bytes = little.long
                    val failed = little.int
                    if (open != null) throw ExtractProtocolException("DONE while $open is open")
                    expectEof(input, "DONE")
                    return ExtractStreamEnd.Done(entries, bytes, failed)
                }
                TAG_ABORT -> {
                    readExactly(input, header, 2)
                    little.position(0)
                    val messageLength = little.short.toInt() and 0xFFFF
                    val message = readMessage(input, messageLength)
                    expectEof(input, "ABORT")
                    return ExtractStreamEnd.Aborted(message)
                }
                else -> throw ExtractProtocolException("frame tag 0x${Integer.toHexString(tag)} unknown")
            }
        }
    }

    private fun checkOrdinal(ordinal: Int) {
        if (ordinal < 0) throw ExtractProtocolException("ordinal $ordinal is negative")
        if (ordinal <= lastOrdinal) throw ExtractProtocolException("ordinal $ordinal after $lastOrdinal: not increasing")
        if (!isPlanned(ordinal)) throw ExtractProtocolException("ordinal $ordinal is not in the plan")
    }

    private fun readMessage(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        readExactly(input, bytes, length)
        return String(bytes, Charsets.UTF_8)
    }

    private fun expectEof(input: InputStream, terminal: String) {
        if (input.read() >= 0) throw ExtractProtocolException("bytes after $terminal")
    }

    /** Reads exactly [length] bytes into [buffer], or reports the loss of the stream. */
    private fun readExactly(input: InputStream, buffer: ByteArray, length: Int) {
        val got = readUpTo(input, buffer, length)
        if (got < length) throw StreamCut()
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray, length: Int): Int {
        var at = 0
        while (at < length) {
            val n = input.read(buffer, at, length - at)
            if (n < 0) break
            at += n
        }
        return at
    }

    /** An EOF inside a frame: [read] turns it into [ExtractStreamEnd.TransportLoss]. */
    private class StreamCut : EOFException("extraction stream cut inside a frame")

    companion object {
        private val MAGIC = "FZX1".toByteArray(Charsets.US_ASCII)

        const val TAG_BEGIN = 0x01
        const val TAG_DATA = 0x02
        const val TAG_END = 0x03
        const val TAG_FAIL = 0x04
        const val TAG_DONE = 0x05
        const val TAG_ABORT = 0x06

        const val KIND_FILE = 1
        const val KIND_DIRECTORY = 2
        const val KIND_SYMLINK = 3
        const val KIND_HARDLINK = 4
        const val KIND_OTHER = 5

        const val WARN_NONE = 0
        const val WARN_OTHER = 1

        const val FAIL_OTHER = 0
        const val FAIL_CRC = 1
        const val FAIL_SIZE = 2
        const val FAIL_DECODE = 3

        const val DECLARED_UNKNOWN = -1L
        const val MAX_DATA_FRAME_BYTES = 1024L * 1024L
        const val MAX_PATH_BYTES = 64 * 1024

        private const val HEADER_BYTES = 32
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
