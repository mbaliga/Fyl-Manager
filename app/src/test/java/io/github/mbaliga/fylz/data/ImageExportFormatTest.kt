package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The extension/mime/codec mapping [AnnotateOverlay] and [ConvertImageFormatOverlay] both depend
 * on to write a real, openable file -- a wrong pairing here (a ".png" name carrying JPEG bytes,
 * say) is a corrupt file on disk, not a test failure that stays contained.
 */
class ImageExportFormatTest {

    @Test
    fun `every format's codec and extension actually agree with its own mime type`() {
        assertEquals(Bitmap.CompressFormat.JPEG, ImageExportFormat.JPEG.compressFormat)
        assertEquals("jpg", ImageExportFormat.JPEG.extension)
        assertEquals("image/jpeg", ImageExportFormat.JPEG.mimeType)

        assertEquals(Bitmap.CompressFormat.PNG, ImageExportFormat.PNG.compressFormat)
        assertEquals("png", ImageExportFormat.PNG.extension)
        assertEquals("image/png", ImageExportFormat.PNG.mimeType)

        assertEquals(Bitmap.CompressFormat.WEBP_LOSSLESS, ImageExportFormat.WEBP.compressFormat)
        assertEquals("webp", ImageExportFormat.WEBP.extension)
        assertEquals("image/webp", ImageExportFormat.WEBP.mimeType)
    }

    @Test
    fun `only JPEG is actually lossy`() {
        assertEquals(92, ImageExportFormat.JPEG.quality)
        assertEquals(100, ImageExportFormat.PNG.quality)
        assertEquals(100, ImageExportFormat.WEBP.quality)
    }

    @Test
    fun `fromMimeType round-trips every format and rejects the rest`() {
        ImageExportFormat.entries.forEach { format ->
            assertEquals(format, ImageExportFormat.fromMimeType(format.mimeType))
        }
        assertNull(ImageExportFormat.fromMimeType("image/gif"))
        assertNull(ImageExportFormat.fromMimeType("image/bmp"))
        assertNull(ImageExportFormat.fromMimeType("application/octet-stream"))
    }

    @Test
    fun `the supported set is exactly the three formats, nothing silently added or dropped`() {
        assertEquals(
            setOf("image/jpeg", "image/png", "image/webp"),
            ImageExportFormat.SUPPORTED_MIME_TYPES,
        )
    }
}
