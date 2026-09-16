package io.github.mbaliga.fylz.data

import android.graphics.Bitmap

/**
 * Raster formats this app can actually write, because they are what `Bitmap.compress` supports --
 * not every format [io.github.mbaliga.fylz.core.model.EntryKind.IMAGE] covers. GIF, BMP and HEIC
 * decode fine as a preview but have no encoder here, so they are deliberately absent from this
 * list rather than offered and silently mapped onto one of these three.
 *
 * Shared by [AnnotateOverlay] (saving back in the source's own format) and
 * [ConvertImageFormatOverlay] (saving in a format the user picked instead) -- one enum, so the
 * extension/mime/codec mapping cannot drift between the two.
 */
enum class ImageExportFormat(val extension: String, val mimeType: String, val label: String) {
    JPEG("jpg", "image/jpeg", "JPEG"),
    PNG("png", "image/png", "PNG"),
    WEBP("webp", "image/webp", "WebP"),
    ;

    val compressFormat: Bitmap.CompressFormat
        get() = when (this) {
            JPEG -> Bitmap.CompressFormat.JPEG
            PNG -> Bitmap.CompressFormat.PNG
            WEBP -> Bitmap.CompressFormat.WEBP_LOSSLESS
        }

    /** JPEG is genuinely lossy; PNG and WebP-lossless both ignore this value. */
    val quality: Int get() = if (this == JPEG) 92 else 100

    companion object {
        /**
         * What [io.github.mbaliga.fylz.ui.AnnotateOverlay] and
         * [io.github.mbaliga.fylz.ui.ConvertImageFormatOverlay] both gate opening on.
         */
        val SUPPORTED_MIME_TYPES: Set<String> by lazy { entries.map { it.mimeType }.toSet() }

        fun fromMimeType(mimeType: String): ImageExportFormat? = entries.firstOrNull { it.mimeType == mimeType }
    }
}
