package io.github.mbaliga.fylz.formats

/*
 * The format model: what a file type IS, independent of Android.
 *
 * Everything in this package is pure Kotlin/JVM (no android.* imports) so it can be compiled and
 * unit-tested off-device. The app adapts a ContentResolver stream to [ByteSource] and calls in.
 */

/** Broad family, used for grouping, iconography and choosing a viewer. */
enum class FormatCategory {
    DOCUMENT, SPREADSHEET, PRESENTATION, EBOOK, PUBLISHING,
    IMAGE, RAW_PHOTO, VECTOR, ICON, FONT,
    AUDIO, VIDEO, SUBTITLE, MUSIC_NOTATION, PLAYLIST,
    ARCHIVE, COMPRESSION, DISK_IMAGE, BACKUP, PACKAGE, INSTALLER,
    EXECUTABLE, LIBRARY, BYTECODE, FIRMWARE, ROM,
    DATABASE, DATA, SERIALIZATION, LOG, CONFIG, MARKUP, TEXT, SOURCE_CODE, BUILD, NOTEBOOK,
    CAD, MODEL_3D, EDA, MANUFACTURING, GIS, SCIENTIFIC, MEDICAL, STATISTICAL, ENGINEERING,
    EMAIL, CALENDAR, CONTACT, CERTIFICATE, CRYPTO, SYSTEM, SHORTCUT, VIRTUALIZATION,
    GAME, FINANCIAL, GENEALOGY, LOCALIZATION, EDUCATION, MAP, OTHER,
}

/** One run of bytes that must appear at a fixed [offset]. A `mask` byte of 0 is a wildcard. */
class Magic(val offset: Int, val bytes: ByteArray, val mask: ByteArray? = null) {
    init {
        require(offset >= 0) { "offset must be >= 0" }
        require(bytes.isNotEmpty()) { "a magic run cannot be empty" }
        require(mask == null || mask.size == bytes.size) { "mask must match bytes in length" }
    }

    val end: Int get() = offset + bytes.size

    /** Number of bytes this run actually constrains (wildcards excluded): its specificity. */
    val specificity: Int get() = mask?.count { it.toInt() != 0 } ?: bytes.size

    fun matches(data: ByteArray): Boolean {
        if (end > data.size) return false
        for (i in bytes.indices) {
            val m = mask?.get(i)?.toInt()?.and(0xFF) ?: 0xFF
            if ((data[offset + i].toInt() and m) != (bytes[i].toInt() and m)) return false
        }
        return true
    }
}

/** A conjunction of [Magic] runs: ALL must match. A format lists alternatives; any one suffices. */
class Signature(val parts: List<Magic>) {
    init { require(parts.isNotEmpty()) { "a signature needs at least one run" } }

    val end: Int get() = parts.maxOf { it.end }
    val specificity: Int get() = parts.sumOf { it.specificity }

    fun matches(data: ByteArray): Boolean = parts.all { it.matches(data) }

    /** A synthetic header that satisfies this signature (wildcards become 0), for tests. */
    fun sample(): ByteArray {
        val out = ByteArray(end)
        for (p in parts) for (i in p.bytes.indices) out[p.offset + i] = p.bytes[i]
        return out
    }

    operator fun plus(other: Signature) = Signature(parts + other.parts)
}

/** Signature from ASCII text at [offset], e.g. `sig(0, "%PDF-")`. */
fun sig(offset: Int, ascii: String): Signature =
    Signature(listOf(Magic(offset, ascii.toByteArray(Charsets.ISO_8859_1))))

/**
 * Signature from hex at [offset], e.g. `sigHex(0, "89 50 4E 47 0D 0A 1A 0A")`.
 * `??` is a wildcard byte: `sigHex(0, "52 49 46 46 ?? ?? ?? ?? 57 41 56 45")`.
 */
fun sigHex(offset: Int, hex: String): Signature {
    val tokens = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val bytes = ByteArray(tokens.size)
    val mask = ByteArray(tokens.size)
    for ((i, t) in tokens.withIndex()) {
        if (t == "??") { bytes[i] = 0; mask[i] = 0 } else {
            require(t.length == 2) { "bad hex token '$t'" }
            bytes[i] = t.toInt(16).toByte(); mask[i] = 0xFF.toByte()
        }
    }
    val m = if (mask.all { it.toInt() != 0 }) null else mask
    return Signature(listOf(Magic(offset, bytes, m)))
}

/**
 * One file format.
 *
 * ID CONTRACT: [id] is the format's primary file extension, lowercase, no dot (`png`, `step`,
 * `docx`). If one extension names several unrelated formats, qualify it: `dat-quake`, `bin-nes`.
 * Aliases (`jpeg`, `stp`, `tiff`) go in [extensions], NOT in [id]. Canonical ids shared with the
 * inspectors: jpg, tif, step, dcm, nii, h5, nc, exe (Windows PE), elf, macho, sqlite, gz.
 *
 * ACCURACY RULE: a wrong signature is worse than none. Give [signatures] only when certain; a
 * format known only by extension is still valuable. [signatures] must lie within the first
 * [FormatSniffer.MAX_HEADER_BYTES] bytes.
 *
 * @param containerId when this format is a specialisation of a generic container that has its own
 *   signature (docx → "zip", mkv → "ebml", odt → "zip", apk → "zip", m4a → "mp4"), the container's
 *   id. The sniffer uses it to prefer the extension's specific format over the bare container.
 * @param text true when the format is human-readable text (affects the fallback viewer).
 */
class FormatSpec(
    val id: String,
    val label: String,
    val category: FormatCategory,
    val extensions: List<String>,
    val mimeTypes: List<String> = emptyList(),
    val signatures: List<Signature> = emptyList(),
    val containerId: String? = null,
    val text: Boolean = false,
    val description: String = "",
) {
    init {
        require(id.isNotBlank() && id == id.lowercase() && !id.startsWith(".")) { "bad id '$id'" }
        require(extensions.all { it == it.lowercase() && !it.startsWith(".") && it.isNotBlank() }) {
            "extensions must be lowercase, no leading dot: $extensions (format $id)"
        }
    }

    override fun toString() = "FormatSpec($id)"
}

/** One labelled fact about a file. [group] lets a viewer section them ("Header", "Streams"). */
data class Fact(val label: String, val value: String, val group: String? = null)

/** What an inspector learned about a file. Everything is optional except the title. */
data class InspectionResult(
    val formatId: String,
    val title: String,
    val summary: String? = null,
    val facts: List<Fact> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Extracted readable text, capped by the inspector at [FormatInspector.MAX_TEXT_CHARS]. */
    val text: String? = null,
)

/** Random-access, read-only bytes. The app backs it with a file descriptor; tests with an array. */
interface ByteSource {
    val size: Long

    /** Up to [length] bytes from [offset]; shorter at end of file, empty past it. Never throws for range. */
    fun read(offset: Long, length: Int): ByteArray
}

class ByteArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= data.size || length <= 0) return ByteArray(0)
        val end = minOf(data.size.toLong(), offset + length).toInt()
        return data.copyOfRange(offset.toInt(), end)
    }
}
