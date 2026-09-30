package io.github.mbaliga.fylz.archive

/**
 * libarchive's format-family codes (`archive.h`'s `ARCHIVE_FORMAT_*` with the base mask applied),
 * which `ArchiveInspection.formatCode` carries and every UI decision keys on -- never the
 * informational `formatName`. Only [ZIP] decides anything in M3.2 (extraction is still zip4j);
 * the rest name the family for the user.
 */
object ArchiveFormatFamily {
    const val CPIO = 0x10000
    const val SHAR = 0x20000
    const val TAR = 0x30000
    const val ISO9660 = 0x40000
    const val ZIP = 0x50000
    const val EMPTY = 0x60000
    const val AR = 0x70000
    const val MTREE = 0x80000
    const val RAW = 0x90000
    const val XAR = 0xA0000
    const val LHA = 0xB0000
    const val CAB = 0xC0000
    const val RAR = 0xD0000
    const val SEVEN_ZIP = 0xE0000
    const val WARC = 0xF0000
    const val RAR_V5 = 0x100000

    fun isZip(formatCode: Int): Boolean = formatCode == ZIP

    /** A short family name, with the compression filters in parentheses when there are any. */
    fun label(formatCode: Int, filters: List<String> = emptyList()): String {
        val family = when (formatCode) {
            ZIP -> "ZIP archive"
            SEVEN_ZIP -> "7-Zip archive"
            ISO9660 -> "ISO 9660 image"
            TAR -> "tar archive"
            CPIO -> "cpio archive"
            AR -> "ar archive"
            RAR, RAR_V5 -> "RAR archive"
            CAB -> "Cabinet archive"
            XAR -> "xar archive"
            LHA -> "LHA archive"
            WARC -> "WARC archive"
            MTREE -> "mtree listing"
            SHAR -> "shar archive"
            RAW -> "compressed stream"
            EMPTY -> "empty archive"
            else -> "archive (format 0x${Integer.toHexString(formatCode)})"
        }
        return if (filters.isEmpty()) family else "$family (${filters.joinToString(", ")})"
    }
}
