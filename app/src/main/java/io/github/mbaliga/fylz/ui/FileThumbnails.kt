package io.github.mbaliga.fylz.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import io.github.mbaliga.fylz.ui.places.RecentFile
import io.github.mbaliga.fylz.ui.places.StorageCategory

/**
 * Real thumbnails for the files shown inside the landing folders, so a folder previews what is
 * actually in it instead of a stand-in.
 *
 * Per category, cheapest honest source first: the media provider's own thumbnail for photos,
 * videos and audio (a real video frame, album art); the first page for a PDF; the app's own icon
 * for an APK. Anything else, and any failure, returns null and the caller draws a labelled page
 * instead. Every path is bounded to [PIXELS] square and runs off the main thread.
 */
internal object FileThumbnails {

    private const val PIXELS = 320

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private const val CACHE_BYTES = 12 * 1024 * 1024

    fun cached(file: RecentFile): Bitmap? = cache.get(file.uri.toString())

    /** Blocking; call from an IO dispatcher. Never throws. */
    fun load(context: Context, file: RecentFile, category: StorageCategory): Bitmap? {
        cached(file)?.let { return it }
        val bitmap = runCatching {
            when {
                category == StorageCategory.APKS -> apkIcon(context, file)
                file.mimeType == "application/pdf" || file.name.endsWith(".pdf", ignoreCase = true) -> pdfFirstPage(context, file)
                else -> context.contentResolver.loadThumbnail(file.uri, Size(PIXELS, PIXELS), CancellationSignal())
            }
        }.getOrNull()
        if (bitmap != null) cache.put(file.uri.toString(), bitmap)
        return bitmap
    }

    private fun pdfFirstPage(context: Context, file: RecentFile): Bitmap? {
        context.contentResolver.openFileDescriptor(file.uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val width = PIXELS
                    val height = (width * page.height.toFloat() / page.width).toInt().coerceIn(1, PIXELS * 2)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
        return null
    }

    private fun apkIcon(context: Context, file: RecentFile): Bitmap? {
        val path = file.path ?: return null
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(path, PackageManager.GET_META_DATA) ?: return null
        val app = info.applicationInfo ?: return null
        // Without these the icon resolves against this app's own resources, not the archive's.
        app.sourceDir = path
        app.publicSourceDir = path
        val drawable = app.loadIcon(pm)
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val size = PIXELS
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
