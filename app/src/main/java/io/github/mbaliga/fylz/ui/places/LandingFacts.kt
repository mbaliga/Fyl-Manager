package io.github.mbaliga.fylz.ui.places

import android.net.Uri

/**
 * What the storage card breaks the volume into. Order is the order the card reads and the order
 * the bar's segments run.
 */
enum class StorageCategory(val label: String) {
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    DOCUMENTS("Documents"),
    AUDIO("Audio"),
    APKS("APKs"),
}

/** Bytes and file count for one [StorageCategory]. */
data class CategoryUsage(val bytes: Long, val count: Int)

/**
 * The landing screen's numbers, kept apart from how they are drawn so the classification, the
 * bar's maths and the wording are asserted in tests instead of eyeballed on a device.
 */
data class StorageBreakdown(val usage: Map<StorageCategory, CategoryUsage> = emptyMap()) {

    fun bytes(category: StorageCategory): Long = usage[category]?.bytes ?: 0L

    fun count(category: StorageCategory): Int = usage[category]?.count ?: 0

    /**
     * The bar: each category's share of the whole volume, in card order, as fractions of
     * [totalBytes]. Empty categories are left out. A category that does exist is never thinner
     * than [MIN_SEGMENT] (a 2 MB APK folder on a 128 GB phone would otherwise be invisible), and if
     * the shares add up to more than the volume (stale figures, a clone app's files counted
     * twice) they are scaled down together so the bar never overflows its track.
     */
    fun segments(totalBytes: Long): List<Pair<StorageCategory, Float>> {
        if (totalBytes <= 0L) return emptyList()
        val raw = StorageCategory.entries
            .filter { bytes(it) > 0L }
            .map { it to (bytes(it).toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat().coerceAtLeast(MIN_SEGMENT) }
        val sum = raw.sumOf { it.second.toDouble() }
        if (sum <= 1.0) return raw
        return raw.map { (category, share) -> category to (share / sum).toFloat() }
    }

    companion object {
        const val MIN_SEGMENT = 0.012f
    }
}

/** Where a file goes on the storage card. Pure: a MIME type and a name in, a category out. */
object StorageClassifier {

    private val APK_EXTENSIONS = setOf("apk", "xapk", "apks", "apkm")

    private val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "txt", "rtf", "csv", "md", "epub",
    )

    private val DOCUMENT_MIME_FRAGMENTS = listOf(
        "pdf", "msword", "officedocument", "ms-excel", "ms-powerpoint", "opendocument", "rtf", "epub",
    )

    fun classify(mimeType: String?, fileName: String?): StorageCategory? {
        val mime = mimeType.orEmpty().lowercase()
        val extension = fileName.orEmpty().substringAfterLast('.', "").lowercase()
        return when {
            extension in APK_EXTENSIONS || mime == "application/vnd.android.package-archive" -> StorageCategory.APKS
            mime.startsWith("image/") -> StorageCategory.PHOTOS
            mime.startsWith("video/") -> StorageCategory.VIDEOS
            mime.startsWith("audio/") -> StorageCategory.AUDIO
            // Not "text/*": that would sweep in .json, .html and .css, which the Documents tile's
            // search (the extension list below) would not return, so the count and the list
            // would disagree.
            DOCUMENT_MIME_FRAGMENTS.any { it in mime } ||
                extension in DOCUMENT_EXTENSIONS -> StorageCategory.DOCUMENTS
            else -> null
        }
    }
}

/**
 * The four category tiles under the storage card. Each opens a recursive search over the device
 * volume, the same mechanism "Large files" uses, so a tile shows *every* photo or document and
 * not just the ones that happen to sit in one folder.
 */
enum class LauncherCategory(
    val label: String,
    val storage: StorageCategory,
    /** A query [io.github.mbaliga.fylz.search.SearchQuery] parses. */
    val query: String,
) {
    DOCUMENTS("Documents", StorageCategory.DOCUMENTS, "ext:pdf,doc,docx,xls,xlsx,ppt,pptx,odt,ods,odp,txt,rtf,csv,md,epub"),
    IMAGES("Images", StorageCategory.PHOTOS, "type:image"),
    VIDEOS("Videos", StorageCategory.VIDEOS, "type:video"),
    APKS("APKs", StorageCategory.APKS, "ext:apk,xapk,apks,apkm"),
}

/** Everything the landing screen reads from the device, gathered in one background pass. */
data class LandingFacts(
    val breakdown: StorageBreakdown = StorageBreakdown(),
    /** Up to three newest photos, newest first: the cards in the Images folder. */
    val recentImages: List<Uri> = emptyList(),
    val downloadsCount: Int = 0,
    val downloadsNewest: String? = null,
    /** False until the first pass finishes, so "Empty" is not shown for a folder still being counted. */
    val loaded: Boolean = false,
)

/** Wording the cards share. */
object LandingText {

    /** "Empty", "1 File", "12 Files". */
    fun fileCount(count: Int, loaded: Boolean = true): String = when {
        !loaded -> ""
        count <= 0 -> "Empty"
        count == 1 -> "1 File"
        else -> "$count Files"
    }

    /** The bin's headline number: exact up to 999, then "1000+" like the reference. */
    fun deletedCount(count: Int): String = if (count >= 1000) "1000+" else count.toString()

    /** "97 GB", "9.5 GB", "420 MB" — one decimal under ten, none above, so a card never wraps. */
    fun compactBytes(bytes: Long): String {
        if (bytes < 1_024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1_024.0
            unit += 1
        } while (value >= 1_024.0 && unit < units.lastIndex)
        val number = if (value >= 10.0) "%.0f".format(value) else "%.1f".format(value)
        return "$number ${units[unit]}"
    }

    /** The card's right-hand figure: "97 GB/128 GB" used over total. */
    fun usedOfTotal(usedBytes: Long, totalBytes: Long): String =
        "${compactBytes(usedBytes.coerceIn(0L, totalBytes))}/${compactBytes(totalBytes)}"
}
