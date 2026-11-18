package io.github.mbaliga.fylz.ui.places

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Reads the landing screen's numbers: one pass over the MediaStore file index for the category
 * sizes and the newest photo, and a shallow look at Downloads.
 *
 * MediaStore is the right source for the breakdown. Walking the whole volume to add up sizes
 * would take minutes on a full phone, while the index already holds every file's size and type.
 * What it can see depends on the access the user granted (with "All files access" it is the
 * whole shared volume), and whatever it cannot see simply is not counted: the card then says less
 * rather than guessing. Every failure degrades to empty facts; the landing screen must always draw.
 */
object LandingFactsLoader {

    private const val RECENT_IMAGES = 3

    fun load(context: Context): LandingFacts {
        var usage = emptyMap<StorageCategory, CategoryUsage>()
        var recentImages = emptyList<android.net.Uri>()
        runCatching {
            val bytes = LongArray(StorageCategory.entries.size)
            val counts = IntArray(StorageCategory.entries.size)
            // The three newest photos as (dateAdded, id), kept sorted newest first.
            val newest = ArrayList<Pair<Long, Long>>(4)
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_ADDED,
            )
            // Folders have no MIME type and a size of zero; the classifier drops them.
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val category = StorageClassifier.classify(cursor.getString(mimeCol), cursor.getString(nameCol)) ?: continue
                    val i = category.ordinal
                    bytes[i] += cursor.getLong(sizeCol).coerceAtLeast(0L)
                    counts[i] += 1
                    if (category == StorageCategory.PHOTOS) {
                        val added = cursor.getLong(addedCol)
                        if (newest.size < RECENT_IMAGES || added > newest.last().first) {
                            newest.add(added to cursor.getLong(idCol))
                            newest.sortByDescending { it.first }
                            if (newest.size > RECENT_IMAGES) newest.removeAt(newest.lastIndex)
                        }
                    }
                }
            }
            recentImages = newest.map { (_, id) ->
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            usage = StorageCategory.entries.associateWith { CategoryUsage(bytes[it.ordinal], counts[it.ordinal]) }
        }

        var downloadsCount = 0
        var downloadsNewest: String? = null
        runCatching {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val children = downloads.listFiles { f: File -> !f.name.startsWith(".") }.orEmpty()
            downloadsCount = children.size
            downloadsNewest = children.maxByOrNull { it.lastModified() }?.name
        }

        return LandingFacts(
            breakdown = StorageBreakdown(usage),
            recentImages = recentImages,
            downloadsCount = downloadsCount,
            downloadsNewest = downloadsNewest,
            loaded = true,
        )
    }
}
