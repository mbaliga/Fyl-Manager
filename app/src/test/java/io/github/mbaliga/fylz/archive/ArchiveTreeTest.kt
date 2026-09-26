package io.github.mbaliga.fylz.archive

import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ArchiveTree`'s normalisation (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.3), on the
 * golden listings of `messy-paths.tar`, `backslash.zip`, `implicit-dirs.zip`, `mixed-links.tar`
 * and `dot-rooted.tar` as the Rust writer produced them (`ArchiveListingCodecTest` decodes the same
 * files), plus hand-built listings for the shapes those fixtures do not carry. Plain JVM.
 */
class ArchiveTreeTest {

    private fun golden(name: String): ArchiveListing {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            for (relative in listOf("app/src/test/resources/fixtures/archives/$name", "src/test/resources/fixtures/archives/$name")) {
                val candidate = dir?.let { File(it, relative) }
                if (candidate?.isFile == true) return ArchiveListingCodec.decode(candidate, 200_000)
            }
            dir = dir?.parentFile
        }
        error("golden $name not found")
    }

    private fun tree(name: String, formatCode: Int) = ArchiveTree.build(golden(name), formatCode)

    private fun names(entries: List<ArchiveTreeEntry>) = entries.map { it.name }

    @Test
    fun `normalize handles every shape`() {
        fun n(raw: String) = ArchiveTree.normalize(raw, zipBackslashes = false)
        assertEquals("a", n("./a"))
        assertEquals("a", n("././a"))
        assertEquals("dir", n("dir/"))
        assertEquals("abs", n("/abs"))
        assertEquals("c/d", n("c//d"))
        assertEquals("dot/e", n("dot/./e"))
        assertEquals("f", n("in/../f"))
        assertNull("climbs out", n("../escape"))
        assertNull("climbs out deep", n("a/../../b"))
        assertEquals("", n("."))
        assertEquals("", n("./"))
        assertEquals("", n("/"))
        assertEquals("", n(""))
        assertEquals("dir\\file.txt", n("dir\\file.txt"))
        assertEquals("dir/file.txt", ArchiveTree.normalize("dir\\file.txt", zipBackslashes = true))
        assertEquals("a/b", ArchiveTree.normalize("a\\\\b", zipBackslashes = true))
    }

    @Test
    fun `messy-paths tar normalises every case, quarantines the unsafe ones and keeps case distinct`() {
        val tree = tree("messy-paths.fzl", ArchiveFormatFamily.TAR)
        assertEquals(
            listOf("a", "dir", "abs", "c", "dot", "f", "dup.txt", "both", "README", "readme"),
            names(tree.children("")),
        )
        // `./a` -> `a`, a file with its own ordinal.
        assertEquals(0, tree.entry("a")!!.ordinal)
        assertTrue(tree.entry("a")!!.isFile)
        // `dir/` -> `dir`, explicit, ordinal 1; its child under it.
        assertEquals(1, tree.entry("dir")!!.ordinal)
        assertEquals(listOf("x.txt"), names(tree.children("dir")))
        // `/abs` -> `abs`.
        assertEquals(3, tree.entry("abs")!!.ordinal)
        // `c//d` -> `c/d` with `c` synthesised.
        assertTrue(tree.entry("c")!!.isImplicit)
        assertEquals(4, tree.entry("c/d")!!.ordinal)
        // `dot/./e` -> `dot/e`.
        assertEquals(5, tree.entry("dot/e")!!.ordinal)
        // `in/../f` -> `f`; no `in`.
        assertEquals(7, tree.entry("f")!!.ordinal)
        assertNull(tree.entry("in"))
        // `../escape` quarantined; `both` (file) lost to `both/` (directory): two quarantined.
        assertEquals(2, tree.quarantined)
        assertNull(tree.entry("escape"))
        // Last member wins for `dup.txt`: ordinal 9, in the position of the first appearance.
        assertEquals(9, tree.entry("dup.txt")!!.ordinal)
        // File-vs-directory collision: the directory wins, the file is gone.
        assertTrue(tree.entry("both")!!.isDirectory)
        assertTrue(tree.entry("both")!!.isImplicit)
        assertEquals(listOf("inside.txt"), names(tree.children("both")))
        // Case is significant.
        assertEquals(12, tree.entry("README")!!.ordinal)
        assertEquals(13, tree.entry("readme")!!.ordinal)
        // Children are unique by name.
        val childNames = names(tree.children(""))
        assertEquals(childNames.size, childNames.toSet().size)
        assertEquals(3, tree.implicitDirectories)
    }

    @Test
    fun `backslash separators are rewritten for ZIP only`() {
        // `backslash.zip`'s member is named `dir\file.txt`, yet its golden listing already reads
        // `dir/file.txt`: libarchive's ZIP reader rewrites the separator before the engine sees it,
        // so on that fixture the tree's own rule has nothing left to do (recorded as an observation).
        val fromFixture = tree("backslash.fzl", ArchiveFormatFamily.ZIP)
        assertEquals(listOf("dir", "top.txt"), names(fromFixture.children("")))
        assertEquals(listOf("file.txt"), names(fromFixture.children("dir")))
        // The rule itself, on a listing that carries the raw backslash (a reader that did not rewrite).
        val raw = ArchiveListing(
            listOf(
                ArchiveListingTestWriter.record(0, "dir\\file.txt", uncompressedBytes = 10),
                ArchiveListingTestWriter.record(1, "top.txt", uncompressedBytes = 4),
            ),
            partial = false,
        )
        val zip = ArchiveTree.build(raw, ArchiveFormatFamily.ZIP)
        assertEquals(listOf("dir", "top.txt"), names(zip.children("")))
        assertEquals(listOf("file.txt"), names(zip.children("dir")))
        assertEquals("dir/file.txt", zip.entry("dir/file.txt")!!.path)
        assertTrue(zip.entry("dir")!!.isImplicit)
        val tar = ArchiveTree.build(raw, ArchiveFormatFamily.TAR)
        assertEquals(listOf("dir\\file.txt", "top.txt"), names(tar.children("")))
        assertEquals(0, tar.implicitDirectories)
    }

    @Test
    fun `implicit directories are synthesised with the implicit ordinal and an unknown mtime`() {
        val tree = tree("implicit-dirs.fzl", ArchiveFormatFamily.ZIP)
        assertEquals(listOf("a", "top.txt"), names(tree.children("")))
        assertEquals(listOf("b", "d.txt"), names(tree.children("a")))
        assertEquals(listOf("c.txt"), names(tree.children("a/b")))
        val a = tree.entry("a")!!
        assertTrue(a.isDirectory && a.isImplicit)
        assertEquals(ArchiveDocumentId.IMPLICIT_ORDINAL, a.ordinal)
        assertFalse(a.mtimeKnown)
        assertFalse(a.sizeKnown)
        assertEquals(2, tree.implicitDirectories)
        assertEquals(0, tree.quarantined)
        assertEquals(5, tree.size)
        assertTrue(tree.isDirectory(""))
        assertTrue(tree.isDirectory("a/b"))
        assertFalse(tree.isDirectory("top.txt"))
        assertFalse(tree.isDirectory("nope"))
    }

    @Test
    fun `an explicit directory row later takes over an implicit one, keeping its position`() {
        val listing = ArchiveListing(
            listOf(
                ArchiveListingTestWriter.record(0, "a/b.txt", uncompressedBytes = 1),
                ArchiveListingTestWriter.record(1, "z.txt", uncompressedBytes = 1),
                ArchiveListingTestWriter.directory(2, "a/"),
            ),
            partial = false,
        )
        val tree = ArchiveTree.build(listing, ArchiveFormatFamily.ZIP)
        assertEquals(listOf("a", "z.txt"), names(tree.children("")))
        assertEquals(2, tree.entry("a")!!.ordinal)
        assertFalse(tree.entry("a")!!.isImplicit)
        assertEquals(0, tree.implicitDirectories)
    }

    @Test
    fun `the dot root of an ISO or tar is the root, not an entry, and entries under it lose the prefix`() {
        val tree = tree("dot-rooted.fzl", ArchiveFormatFamily.TAR)
        assertEquals(listOf("first.txt", "sub"), names(tree.children("")))
        assertEquals(1, tree.entry("first.txt")!!.ordinal)
        assertEquals(2, tree.entry("sub")!!.ordinal)
        assertEquals(listOf("second.txt"), names(tree.children("sub")))
        assertEquals(0, tree.quarantined)
        // Even when the listing does carry the root (the engine drops it, but the reader must agree).
        val withRoot = ArchiveListing(
            listOf(
                ArchiveListingTestWriter.directory(0, "."),
                ArchiveListingTestWriter.record(1, "./x", uncompressedBytes = 1),
                ArchiveListingTestWriter.directory(2, "./"),
            ),
            partial = false,
        )
        val built = ArchiveTree.build(withRoot, ArchiveFormatFamily.ISO9660)
        assertEquals(listOf("x"), names(built.children("")))
        assertEquals(0, built.quarantined)
        assertNull(built.entry(""))
        // A *file* with no name after normalisation is unsafe.
        val nameless = ArchiveListing(listOf(ArchiveListingTestWriter.record(0, "/", uncompressedBytes = 1)), partial = false)
        assertEquals(1, ArchiveTree.build(nameless, ArchiveFormatFamily.TAR).quarantined)
    }

    @Test
    fun `a file in the way of a later path becomes a directory and the file is quarantined`() {
        val listing = ArchiveListing(
            listOf(
                ArchiveListingTestWriter.record(0, "f", uncompressedBytes = 1),
                ArchiveListingTestWriter.record(1, "f/g", uncompressedBytes = 1),
            ),
            partial = false,
        )
        val tree = ArchiveTree.build(listing, ArchiveFormatFamily.TAR)
        assertTrue(tree.entry("f")!!.isDirectory)
        assertEquals(1, tree.quarantined)
        assertEquals(listOf("g"), names(tree.children("f")))
        // The reverse order: a file arriving where a directory already is.
        val reversed = ArchiveListing(
            listOf(
                ArchiveListingTestWriter.directory(0, "d/"),
                ArchiveListingTestWriter.record(1, "d", uncompressedBytes = 1),
            ),
            partial = false,
        )
        val tree2 = ArchiveTree.build(reversed, ArchiveFormatFamily.TAR)
        assertTrue(tree2.entry("d")!!.isDirectory)
        assertEquals(0, tree2.entry("d")!!.ordinal)
        assertEquals(1, tree2.quarantined)
    }

    @Test
    fun `mixed-links tar resolves the hardlink to its target and no other kind`() {
        val tree = tree("mixed-links.fzl", ArchiveFormatFamily.TAR)
        val hardlink = tree.entry("hard-to-target")!!
        assertTrue(hardlink.isHardlink)
        val target = tree.resolveHardlink(hardlink)
        assertNotNull(target)
        assertEquals("target.txt", target!!.path)
        assertEquals(0, target.ordinal)
        assertNull(tree.resolveHardlink(tree.entry("link-to-target")!!))
        assertNull(tree.resolveHardlink(tree.entry("target.txt")!!))
        assertTrue(tree.entry("fifo")!!.isOther)
        assertTrue(tree.entry("link-to-target")!!.isSymlink)
        assertEquals("target.txt", tree.entry("link-to-target")!!.linkTarget)
        assertEquals(listOf("inner.txt"), names(tree.children("dir")))
        // A hardlink to a missing or non-file target does not resolve.
        val dangling = ArchiveListing(
            listOf(
                ArchiveListingTestWriter.directory(0, "d/"),
                ArchiveListingTestWriter.record(1, "h1", kind = ArchiveEntryInfo.KIND_HARDLINK, linkTarget = "missing"),
                ArchiveListingTestWriter.record(2, "h2", kind = ArchiveEntryInfo.KIND_HARDLINK, linkTarget = "d/"),
                ArchiveListingTestWriter.record(3, "h3", kind = ArchiveEntryInfo.KIND_HARDLINK, linkTarget = null),
            ),
            partial = false,
        )
        val t = ArchiveTree.build(dangling, ArchiveFormatFamily.TAR)
        assertNull(t.resolveHardlink(t.entry("h1")!!))
        assertNull(t.resolveHardlink(t.entry("h2")!!))
        assertNull(t.resolveHardlink(t.entry("h3")!!))
    }

    @Test
    fun `entry metadata is carried through and names are the last segment`() {
        val tree = tree("messy-paths.fzl", ArchiveFormatFamily.TAR)
        val x = tree.entry("dir/x.txt")!!
        assertEquals("x.txt", x.name)
        assertEquals("dir", x.parentPath)
        assertEquals(2L, x.uncompressedBytes)
        assertEquals(1_577_836_800L, x.mtimeEpochSeconds)
        assertEquals(0x1a4, x.mode)
        assertTrue(x.sizeKnown && x.mtimeKnown)
        assertEquals("", tree.entry("a")!!.parentPath)
        assertEquals(ArchiveFormatFamily.TAR, tree.formatCode)
    }

    @Test
    fun `a lossy entry's raw path bytes survive into the tree, and only there`() {
        val tree = tree("legacy-cp437.fzl", ArchiveFormatFamily.ZIP)
        assertEquals(1, tree.size)
        val entry = tree.entry("caf�.txt")!!
        assertTrue(entry.nameLossy)
        assertEquals("caf�.txt", entry.name)
        assertEquals(listOf(0x63, 0x61, 0x66, 0x82, 0x2E, 0x74, 0x78, 0x74).map { it.toByte() }, entry.rawPathBytes)
        // The round trip the M3.7 brief asks for: the real fixture's raw bytes, carried through the
        // tree untouched, decode under the charset override to the name a legacy tool actually
        // meant -- ArchiveTree itself knows nothing about charsets (ArchiveRef/ArchiveEncodingOverrides
        // need a real android.net.Uri, so that last hop is `ArchiveEncodingOverridesTest`'s, this
        // file being plain-JVM).
        assertEquals("café.txt", LegacyZipCharsetDetector.decode(entry.rawPathBytes!!.toByteArray(), ArchiveNameEncoding.AUTO))
        // Every other tree entry (none of this fixture's, but the general contract) carries no raw
        // bytes at all.
        assertNull(tree("sample-cd.fzl", ArchiveFormatFamily.ZIP).entry("hello.txt")!!.rawPathBytes)
    }
}
