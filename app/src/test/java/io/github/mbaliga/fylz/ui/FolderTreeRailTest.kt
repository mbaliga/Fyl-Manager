package io.github.mbaliga.fylz.ui

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [flattenInto]'s depth bookkeeping in isolation, away from a composition.
 *
 * The row's own `depth` is the whole spine contract: [FolderTreeRail]'s rows draw an ancestor
 * hairline for every column `0 until depth`, with no separate "still has more descendants" flag
 * the way [io.github.mbaliga.fylz.ui.components.buildCliRows]' text guides need. That shortcut
 * only holds because this list is a single tree with one root -- `flattenInto` only ever
 * recurses while it is still walking a node's own subtree, so a row's depth drops back to (or
 * below) an ancestor's depth in the exact row after that ancestor's last descendant. These tests
 * lock in the depth sequence that guarantee rests on.
 */
@RunWith(RobolectricTestRunner::class)
class FolderTreeRailTest {

    private fun folder(name: String) = FolderLocation(Uri.parse("content://test/$name"), name)

    private fun dir(name: String) = FileEntry(
        uri = Uri.parse("content://test/$name"),
        name = name,
        mimeType = "vnd.android.document/directory",
        sizeBytes = null,
        lastModifiedMillis = null,
        flags = 0,
        kind = EntryKind.DIRECTORY,
    )

    @Test
    fun `a collapsed root emits only itself`() {
        val root = folder("Home")
        val out = mutableListOf<TreeRail>()
        flattenInto(root, listOf(root), 0, expanded = emptySet(), children = emptyMap(), out = out)
        assertEquals(listOf(0), out.map { it.depth })
        assertEquals(false, out.single().expanded)
    }

    @Test
    fun `an expanded parent's children sit one level deeper than it`() {
        val root = folder("Home")
        val children = mapOf(root.uri to listOf(dir("Documents"), dir("Downloads")))
        val out = mutableListOf<TreeRail>()
        flattenInto(root, listOf(root), 0, expanded = setOf(root.uri), children = children, out = out)
        assertEquals(listOf("Home" to 0, "Documents" to 1, "Downloads" to 1), out.map { it.location.name to it.depth })
    }

    @Test
    fun `depth returns to the parent's level right after the last descendant`() {
        // Home
        //   Documents (expanded)
        //     Scanned
        //     Marksheets
        //   Downloads
        //   Pictures
        val root = folder("Home")
        val documents = dir("Documents")
        val children = mapOf(
            root.uri to listOf(documents, dir("Downloads"), dir("Pictures")),
            documents.uri to listOf(dir("Scanned"), dir("Marksheets")),
        )
        val out = mutableListOf<TreeRail>()
        flattenInto(
            root,
            listOf(root),
            0,
            expanded = setOf(root.uri, documents.uri),
            children = children,
            out = out,
        )
        // Depth 1 on both sides of the deeper block is what stops Documents' own spine column
        // from bleeding into Downloads and Pictures -- a row's depth alone marks the boundary.
        // Documents' own children come back alphabetised (Marksheets before Scanned), same as
        // the sibling "alphabetised regardless of listing order" test above.
        assertEquals(
            listOf("Home" to 0, "Documents" to 1, "Marksheets" to 2, "Scanned" to 2, "Downloads" to 1, "Pictures" to 1),
            out.map { it.location.name to it.depth },
        )
    }

    @Test
    fun `a collapsed child contributes no rows of its own`() {
        val root = folder("Home")
        val documents = dir("Documents")
        val children = mapOf(root.uri to listOf(documents, dir("Downloads")))
        val out = mutableListOf<TreeRail>()
        // Documents is not in `expanded`, so its own (unread) children must never appear even
        // though `children` above only maps one level deep to begin with.
        flattenInto(root, listOf(root), 0, expanded = setOf(root.uri), children = children, out = out)
        assertEquals(listOf("Home", "Documents", "Downloads"), out.map { it.location.name })
    }

    @Test
    fun `children are folders-only and alphabetised regardless of listing order`() {
        val root = folder("Home")
        val children = mapOf(
            root.uri to listOf(dir("Zebra"), dir("apple"), dir("Mango")),
        )
        val out = mutableListOf<TreeRail>()
        flattenInto(root, listOf(root), 0, expanded = setOf(root.uri), children = children, out = out)
        assertEquals(listOf("Home", "apple", "Mango", "Zebra"), out.map { it.location.name })
    }

    @Test
    fun `each rail's path is root-inclusive down to itself`() {
        val root = folder("Home")
        val documents = dir("Documents")
        val children = mapOf(root.uri to listOf(documents))
        val out = mutableListOf<TreeRail>()
        flattenInto(root, listOf(root), 0, expanded = setOf(root.uri), children = children, out = out)
        val documentsRail = out.single { it.location.name == "Documents" }
        assertEquals(listOf("Home", "Documents"), documentsRail.path.map { it.name })
    }
}
