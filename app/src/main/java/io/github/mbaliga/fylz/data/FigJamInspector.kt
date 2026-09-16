package io.github.mbaliga.fylz.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * What [FigJamInspector] recovers from a `.fig`/`.jam` file's embedded preview, without ever
 * rendering the design canvas itself -- Figma and FigJam both ship the design tool's proprietary
 * "kiwi" binary format wrapped in a zip container alongside a flat PNG snapshot and a small JSON
 * manifest, and that snapshot is the only part of the file this app can show honestly.
 */
data class FigJamPreviewData(
    val thumbnail: Bitmap?,
    val name: String?,
    val fileVersion: String?,
    val entryCount: Int,
)

/**
 * Reads the embedded preview out of a `.fig`/`.jam` file's zip container.
 *
 * Modern Figma/FigJam files are a zip: a `thumbnail.png` (or `preview.png`) snapshot, a
 * `meta.json` manifest, and the proprietary "kiwi" binary payload this app never attempts to
 * parse. Older exports are that kiwi binary directly, with no zip wrapper at all -- [inspect]
 * returns `null` for those rather than guessing, and the caller falls back to the universal
 * inspector.
 *
 * Every cap here exists because the zip is untrusted input: [MAX_ENTRIES_SCANNED] and
 * [MAX_TOTAL_BYTES] bound how much of the container this class is willing to walk, and
 * [MAX_ENTRY_BYTES] bounds any single entry (the kiwi payload itself can be large; this class
 * must never read it) before the thumbnail decode reuses the two-pass `BitmapFactory` sizing
 * idiom from [ScanPdfService.decodeBounded] -- adapted to decode an already-bounded in-memory
 * byte array rather than reopening a [Uri], since a zip entry can only be streamed once.
 *
 * Those caps bound every entry alike, including ones [scan] has no interest in keeping: measured
 * directly, `ZipInputStream.closeEntry()`/`getNextEntry()` fully decompresses whatever is left of
 * the *current* entry with no bound of their own before moving on -- a capped 8 MiB read followed
 * by a bare `closeEntry()` against a ~2 GiB hidden entry still took several seconds to drain in a
 * throwaway benchmark. So [scan] never calls either once a read comes up short of its budget;
 * it abandons the walk instead, since closing the [ZipInputStream] itself with an entry still
 * mid-read is the one operation that does not implicitly drain it.
 */
class FigJamInspector {
    fun inspect(resolver: ContentResolver, uri: Uri): FigJamPreviewData? {
        val input = resolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            val buffered = BufferedInputStream(stream)
            if (!looksLikeZip(buffered)) return@use null
            runCatching { scan(buffered) }.getOrNull()
        }
    }

    private fun looksLikeZip(buffered: BufferedInputStream): Boolean {
        buffered.mark(4)
        val header = ByteArray(4)
        var read = 0
        while (read < header.size) {
            val count = buffered.read(header, read, header.size - read)
            if (count < 0) break
            read += count
        }
        buffered.reset()
        if (read < 4) return false
        val b0 = header[0].toInt() and 0xff
        val b1 = header[1].toInt() and 0xff
        val b2 = header[2].toInt() and 0xff
        val b3 = header[3].toInt() and 0xff
        if (b0 != 0x50 || b1 != 0x4b) return false
        // Local file header (0x03 0x04), empty archive (0x05 0x06), or spanned/streamed archive
        // (0x07 0x08) -- the three signatures a zip stream can legally start with.
        return (b2 == 0x03 && b3 == 0x04) || (b2 == 0x05 && b3 == 0x06) || (b2 == 0x07 && b3 == 0x08)
    }

    private fun scan(buffered: InputStream): FigJamPreviewData {
        var thumbnailBytes: ByteArray? = null
        var previewBytes: ByteArray? = null
        var metaBytes: ByteArray? = null
        var totalRead = 0L
        var entriesScanned = 0

        ZipInputStream(buffered).use { zip ->
            scanning@ while (entriesScanned < MAX_ENTRIES_SCANNED) {
                val entry = runCatching { zip.nextEntry }.getOrNull() ?: break@scanning
                entriesScanned += 1
                // A directory entry carries no compressed data of its own, so there is nothing a
                // bounded read would even find -- skip straight to the next `nextEntry()` rather
                // than spend a read call proving that.
                if (entry.isDirectory) continue@scanning

                val baseName = entry.name.substringAfterLast('/')
                val wanted = (baseName.equals("thumbnail.png", ignoreCase = true) && thumbnailBytes == null) ||
                    (baseName.equals("preview.png", ignoreCase = true) && previewBytes == null) ||
                    (baseName.equals("meta.json", ignoreCase = true) && metaBytes == null)

                // Every entry drains through the same bounded reader, wanted or not -- see the
                // class doc for why an unwanted entry can't just be `closeEntry()`d away instead.
                val result = readBounded(zip, remainingBudget(totalRead))
                totalRead += result.bytes.size
                if (wanted) {
                    when {
                        baseName.equals("thumbnail.png", ignoreCase = true) -> thumbnailBytes = result.bytes
                        baseName.equals("preview.png", ignoreCase = true) -> previewBytes = result.bytes
                        baseName.equals("meta.json", ignoreCase = true) -> metaBytes = result.bytes
                    }
                }
                // The entry had more data than the budget allowed -- there is no safe way to
                // advance to the next entry without fully draining this one first (see the class
                // doc), so the walk stops here rather than risk that drain.
                if (result.truncated) break@scanning
            }
        }

        val thumbnail = (thumbnailBytes ?: previewBytes)?.let { decodeBounded(it, MAX_THUMBNAIL_DIMENSION) }
        val meta = metaBytes?.let { parseMeta(it) }
        return FigJamPreviewData(
            thumbnail = thumbnail,
            name = meta?.first,
            fileVersion = meta?.second,
            entryCount = entriesScanned,
        )
    }

    private fun remainingBudget(totalRead: Long): Int =
        minOf(MAX_ENTRY_BYTES.toLong(), MAX_TOTAL_BYTES - totalRead).coerceAtLeast(0).toInt()

    /** [bytes] holds at most [maxBytes]; [truncated] is true when the entry had more behind it. */
    private class BoundedRead(val bytes: ByteArray, val truncated: Boolean)

    private fun readBounded(stream: InputStream, maxBytes: Int): BoundedRead {
        if (maxBytes <= 0) return BoundedRead(ByteArray(0), truncated = true)
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buffer.size, maxBytes - total)
            val count = stream.read(buffer, 0, toRead)
            if (count < 0) return BoundedRead(output.toByteArray(), truncated = false)
            output.write(buffer, 0, count)
            total += count
        }
        // Hit the cap exactly -- one bounded probe byte is enough to tell a coincidental
        // cap-sized entry (nothing left to read) from a truncated one (more behind it), without
        // reading any further into whatever is on the other side.
        val probe = ByteArray(1)
        val hasMore = stream.read(probe, 0, 1) >= 0
        return BoundedRead(output.toByteArray(), truncated = hasMore)
    }

    private fun parseMeta(bytes: ByteArray): Pair<String?, String?>? = runCatching {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val name = json.optString("name").takeIf { it.isNotBlank() }
        val version = json.optString("version").takeIf { it.isNotBlank() }
        name to version
    }.getOrNull()

    /** Two-pass bounds-then-decode, same idiom as `ScanPdfService.decodeBounded`, just against an
     * already-bounded in-memory array instead of a re-openable [Uri]. */
    private fun decodeBounded(bytes: ByteArray, maxDimension: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        const val MAX_ENTRIES_SCANNED = 64
        const val MAX_TOTAL_BYTES = 48L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        const val MAX_THUMBNAIL_DIMENSION = 1280
    }
}
