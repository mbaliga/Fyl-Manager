package io.github.mbaliga.fylz.storage

import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.SortDirection
import io.github.mbaliga.fylz.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Test

class FileSorterTest {
    @Test
    fun keepsFoldersFirstInBothDirections() {
        val entries = listOf(
            entry("z.txt", directory = false),
            entry("a-folder", directory = true),
            entry("a.txt", directory = false),
        )

        assertEquals(
            listOf("a-folder", "a.txt", "z.txt"),
            FileSorter.sort(entries, SortField.NAME, SortDirection.ASCENDING).map { it.name },
        )
        assertEquals(
            listOf("a-folder", "z.txt", "a.txt"),
            FileSorter.sort(entries, SortField.NAME, SortDirection.DESCENDING).map { it.name },
        )
    }

    @Test
    fun keepsUnknownMetadataLastRegardlessOfDirection() {
        val entries = listOf(
            entry("unknown.txt", size = null),
            entry("large.txt", size = 100),
            entry("small.txt", size = 10),
        )

        assertEquals(
            listOf("small.txt", "large.txt", "unknown.txt"),
            FileSorter.sort(entries, SortField.SIZE, SortDirection.ASCENDING).map { it.name },
        )
        assertEquals(
            listOf("large.txt", "small.txt", "unknown.txt"),
            FileSorter.sort(entries, SortField.SIZE, SortDirection.DESCENDING).map { it.name },
        )
    }

    private fun entry(
        name: String,
        directory: Boolean = false,
        size: Long? = 1,
    ) = FileEntry(
        treeUri = "content://tree",
        documentId = name,
        name = name,
        mimeType = if (directory) "vnd.android.document/directory" else "text/plain",
        isDirectory = directory,
        size = size,
        modifiedAt = 1,
        flags = 0,
    )
}
