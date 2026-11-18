package io.github.mbaliga.fylz.ui.places

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Reads the landing screen's numbers: one pass over the MediaStore file index for the category
 * sizes and the newest files in each, and a shallow look at Downloads.
 *
 * MediaStore is the right source for the breakdown. Walking the whole volume to add up sizes
 * would take minutes on a full phone, while the index already holds every file's size and type.
 * What it can see depends on the access the user granted (with "All files access" it is the
 * whole shared volume), and whatever it cannot see simply is not counted: the card then says less
 * rather than guessing. Every failure degrades to empty facts; the landing screen must always draw.
 */
object LandingFactsLoader {

    fun load(context: Context): LandingFacts {
        var usage = emptyMap<StorageCategory, CategoryUsage>()
        var recent = emptyMap<StorageCategory, List<RecentFile>>()
        runCatching {
            val bytes = LongArray(StorageCategory.entries.size)
            val counts = IntArray(StorageCategory.entries.size)
            val newest = StorageCategory.entries.map { NewestFirst<RecentFile>(RECENT_PER_CATEGORY) }
            val files = MediaStore.Files.getContentUri("external")
            @Suppress("DEPRECATION")
            val dataColumn = MediaStore.Files.FileColumns.DATA
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_ADDED,
                dataColumn,
            )
            // Folders have no MIME type and a size of zero; the classifier drops them.
            context.contentResolver.query(files, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val dataCol = cursor.getColumnIndexOrThrow(dataColumn)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeCol)
                    val name = cursor.getString(nameCol)
                    val category = StorageClassifier.classify(mime, name) ?: continue
                    val i = category.ordinal
                    bytes[i] += cursor.getLong(sizeCol).coerceAtLeast(0L)
                    counts[i] += 1
                    // Trashed and hidden files are not "recent" in any sense the user would mean.
                    if (name.isNullOrEmpty() || name.startsWith(".")) continue
                    val id = cursor.getLong(idCol)
                    newest[i].offer(
                        cursor.getLong(addedCol),
                        RecentFile(contentUri(category, id), name, mime, cursor.getString(dataCol)),
                    )
                }
            }
            usage = StorageCategory.entries.associateWith { CategoryUsage(bytes[it.ordinal], counts[it.ordinal]) }
            recent = StorageCategory.entries.associateWith { newest[it.ordinal].toList() }
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
            recent = recent,
            downloadsCount = downloadsCount,
            downloadsNewest = downloadsNewest,
            loaded = true,
        )
    }

    /** The provider-specific collection URI, so `loadThumbnail` finds the right thumbnail source. */
    private fun contentUri(category: StorageCategory, id: Long): Uri {
        val base = when (category) {
            StorageCategory.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            StorageCategory.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            StorageCategory.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            StorageCategory.DOCUMENTS, StorageCategory.APKS -> MediaStore.Files.getContentUri("external")
        }
        return ContentUris.withAppendedId(base, id)
    }
}
