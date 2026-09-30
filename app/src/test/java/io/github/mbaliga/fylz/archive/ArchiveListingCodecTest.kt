package io.github.mbaliga.fylz.archive

import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Kotlin half of the listing codec (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.2)
 * against the golden `.fzl` files the Rust writer produced from the committed fixtures
 * (`FYLZ_WRITE_GOLDEN=1 cargo test -p fylz-archive golden` regenerates them; the Rust side asserts
 * byte-equality, this side decodes). Plain JVM: nothing here touches Android.
 */
class ArchiveListingCodecTest {

    private fun golden(name: String): ByteArray {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = dir?.let { File(it, "app/src/test/resources/fixtures/archives/$name") }
            if (candidate?.isFile == true) return candidate.readBytes()
            val direct = dir?.let { File(it, "src/test/resources/fixtures/archives/$name") }
            if (direct?.isFile == true) return direct.readBytes()
            dir = dir?.parentFile
        }
        error("golden $name not found from ${System.getProperty("user.dir")}")
    }

    private fun decode(bytes: ByteArray, maxEntries: Int = 200_000) = ArchiveListingCodec.decode(bytes, maxEntries)

    @Test
    fun `sample-cd fzl decodes to the eight entries of the fixture in archive order with ordinals 0 to 7`() {
        val listing = decode(golden("sample-cd.fzl"))
        assertFalse(listing.partial)
        assertEquals(
            listOf("docs/", "docs/notes/", "images/", "docs/notes/todo.txt", "docs/readme.md", "hello.txt", "images/gradient.bin", "images/pixel.bin"),
            listing.records.map { it.path },
        )
        assertEquals((0..7).toList(), listing.records.map { it.ordinal })
        val docs = listing.records[0]
        assertTrue(docs.isDirectory)
        assertEquals(0x1ed, docs.mode)
        assertEquals(1_577_836_800L, docs.mtimeEpochSeconds)
        val hello = listing.records.single { it.path == "hello.txt" }
        assertTrue(hello.isFile)
        assertEquals(11L, hello.uncompressedBytes)
        assertEquals(0x1a4, hello.mode)
        assertEquals(0, hello.flags)
        assertNull(hello.linkTarget)
    }

    @Test
    fun `mixed-links fzl carries every kind and the link targets`() {
        val listing = decode(golden("mixed-links.fzl"))
        val byPath = listing.records.associateBy { it.path }
        assertEquals(ArchiveEntryInfo.KIND_FILE, byPath.getValue("target.txt").kind)
        val symlink = byPath.getValue("link-to-target")
        assertEquals(ArchiveEntryInfo.KIND_SYMLINK, symlink.kind)
        assertEquals("target.txt", symlink.linkTarget)
        assertTrue(symlink.flags and ArchiveListingCodec.FLAG_HAS_LINK_TARGET != 0)
        val hardlink = byPath.getValue("hard-to-target")
        assertEquals(ArchiveEntryInfo.KIND_HARDLINK, hardlink.kind)
        assertEquals("target.txt", hardlink.linkTarget)
        assertEquals(ArchiveEntryInfo.KIND_OTHER, byPath.getValue("fifo").kind)
        // libarchive's tar reader gives directories a trailing slash whatever the header said.
        assertEquals(ArchiveEntryInfo.KIND_DIRECTORY, byPath.getValue("dir/").kind)
        assertEquals(6, listing.records.size)
    }

    @Test
    fun `dot-rooted fzl starts at ordinal 1 because header 0 was the root the engine dropped`() {
        val listing = decode(golden("dot-rooted.fzl"))
        assertEquals(listOf(1, 2, 3), listing.records.map { it.ordinal })
        assertEquals(listOf("./first.txt", "./sub/", "./sub/second.txt"), listing.records.map { it.path })
    }

    @Test
    fun `damaged-after-3 fzl is partial with its three good records`() {
        val listing = decode(golden("damaged-after-3.fzl"))
        assertTrue(listing.partial)
        assertEquals(listOf("good-0.txt", "good-1.txt", "good-2.txt"), listing.records.map { it.path })
    }

    @Test
    fun `messy-paths and backslash fzls keep raw paths untouched`() {
        assertEquals(
            listOf("./a", "dir/", "dir/x.txt", "/abs", "c//d", "dot/./e", "../escape", "in/../f", "dup.txt", "dup.txt", "both", "both/inside.txt", "README", "readme"),
            decode(golden("messy-paths.fzl")).records.map { it.path },
        )
        // The fixture's member is named `dir\file.txt`, but libarchive's ZIP reader already rewrites
        // the backslash to a slash before the engine sees the name: the listing carries `dir/file.txt`.
        assertEquals(listOf("dir/file.txt", "top.txt"), decode(golden("backslash.fzl")).records.map { it.path })
        // Two members with one path keep two ordinals.
        val dups = decode(golden("messy-paths.fzl")).records.filter { it.path == "dup.txt" }
        assertEquals(listOf(8, 9), dups.map { it.ordinal })
    }

    @Test
    fun `legacy-cp437 fzl carries the raw bytes only for its one lossy record`() {
        val listing = decode(golden("legacy-cp437.fzl"))
        assertEquals(1, listing.records.size)
        val entry = listing.records[0]
        assertTrue(entry.nameLossy)
        assertEquals("caf�.txt", entry.path)
        assertEquals(listOf(0x63, 0x61, 0x66, 0x82, 0x2E, 0x74, 0x78, 0x74).map { it.toByte() }, entry.rawPathBytes)
        // M3.7's own charset override re-decodes those bytes into the name a legacy tool meant.
        assertEquals("café.txt", LegacyZipCharsetDetector.decode(entry.rawPathBytes!!.toByteArray(), ArchiveNameEncoding.CP437))
        assertEquals("café.txt", LegacyZipCharsetDetector.decode(entry.rawPathBytes!!.toByteArray(), ArchiveNameEncoding.AUTO))
    }

    @Test
    fun `every flag, the unknown sentinels and lossy names round-trip through the test writer`() {
        val records = listOf(
            ArchiveListingTestWriter.record(0, "plain.txt", uncompressedBytes = 5L),
            ArchiveListingTestWriter.record(
                1, "caf�.txt",
                uncompressedBytes = ArchiveEntryInfo.UNKNOWN_SIZE,
                mtimeEpochSeconds = ArchiveEntryInfo.UNKNOWN_MTIME,
                encryptedData = true, encryptedMetadata = true, nameLossy = true,
            ),
            ArchiveListingTestWriter.record(2, "link", kind = ArchiveEntryInfo.KIND_SYMLINK, linkTarget = "plain.txt", mode = 0x1ff),
            ArchiveListingTestWriter.directory(3, "d/"),
        )
        val listing = decode(ArchiveListingTestWriter.encode(records, partial = true))
        assertEquals(records, listing.records)
        assertTrue(listing.partial)
        val lossy = listing.records[1]
        assertTrue(lossy.nameLossy && lossy.encryptedData && lossy.encryptedMetadata)
        assertEquals(ArchiveEntryInfo.UNKNOWN_SIZE, lossy.uncompressedBytes)
        assertEquals(ArchiveEntryInfo.UNKNOWN_MTIME, lossy.mtimeEpochSeconds)
        assertEquals("plain.txt", listing.records[2].linkTarget)
    }

    @Test
    fun `200000 entries decode within the budget`() {
        val records = List(200_000) { index ->
            ArchiveListingTestWriter.record(index, "dir${index / 1_000}/subdir${index / 100}/file-$index.txt", uncompressedBytes = index.toLong())
        }
        val bytes = ArchiveListingTestWriter.encode(records)
        val start = System.nanoTime()
        val listing = decode(bytes)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertEquals(200_000, listing.records.size)
        assertEquals("dir199/subdir1999/file-199999.txt", listing.records.last().path)
        println("ArchiveListingCodec: decoded 200,000 records (${bytes.size} bytes) in $elapsedMillis ms")
        assertTrue("decoding 200,000 records took $elapsedMillis ms", elapsedMillis < 10_000)
    }

    // ------------------------------------------------------------------------------------------
    // The file is untrusted.
    // ------------------------------------------------------------------------------------------

    private fun assertCorrupt(message: String, bytes: ByteArray, maxEntries: Int = 200_000): ArchiveListingCorrupt =
        assertThrows(message, ArchiveListingCorrupt::class.java) { decode(bytes, maxEntries) }

    @Test
    fun `a missing or short trailer, a bad magic, and trailing garbage are corrupt`() {
        val one = listOf(ArchiveListingTestWriter.record(0, "a"))
        assertCorrupt("no trailer", ArchiveListingTestWriter.encode(one, withTrailer = false))
        assertCorrupt("empty", ByteArray(0))
        assertCorrupt("magic only", "FZL1".toByteArray())
        assertCorrupt("bad magic", "FZL9".toByteArray() + ArchiveListingTestWriter.encode(one).drop(4).toByteArray())
        val good = ArchiveListingTestWriter.encode(one)
        assertCorrupt("truncated trailer", good.copyOf(good.size - 1))
        assertCorrupt("bytes after the trailer", good + byteArrayOf(0))
    }

    @Test
    fun `a trailer count that disagrees with the records is corrupt`() {
        val two = listOf(ArchiveListingTestWriter.record(0, "a"), ArchiveListingTestWriter.record(1, "b"))
        assertCorrupt("count too high", ArchiveListingTestWriter.encode(two, trailerCount = 3))
        assertCorrupt("count too low", ArchiveListingTestWriter.encode(two, trailerCount = 1))
        assertEquals(2, decode(ArchiveListingTestWriter.encode(two)).records.size)
    }

    @Test
    fun `more records than the caller's bound is corrupt`() {
        val records = List(11) { ArchiveListingTestWriter.record(it, "f$it") }
        assertCorrupt("over the bound", ArchiveListingTestWriter.encode(records), maxEntries = 10)
        assertEquals(11, decode(ArchiveListingTestWriter.encode(records), maxEntries = 11).records.size)
    }

    @Test
    fun `a path or link target over 64 KiB, or longer than what remains, is corrupt`() {
        val longPath = "x".repeat(ArchiveListingCodec.MAX_STRING_BYTES + 1)
        assertCorrupt("path too long", ArchiveListingTestWriter.encode(listOf(ArchiveListingTestWriter.record(0, longPath))))
        val exact = "x".repeat(ArchiveListingCodec.MAX_STRING_BYTES)
        assertEquals(exact, decode(ArchiveListingTestWriter.encode(listOf(ArchiveListingTestWriter.record(0, exact)))).records.single().path)
        assertCorrupt(
            "link target too long",
            ArchiveListingTestWriter.encode(listOf(ArchiveListingTestWriter.record(0, "l", kind = ArchiveEntryInfo.KIND_SYMLINK, linkTarget = longPath))),
        )
        // A length that claims more bytes than the file has left.
        val record = ArchiveListingTestWriter.encodeRecord(ArchiveListingTestWriter.record(0, "abc"))
        val lying = record.copyOf()
        ByteBuffer.wrap(lying).order(ByteOrder.LITTLE_ENDIAN).putInt(5, 1_000)
        assertCorrupt("path runs past the end", "FZL1".toByteArray() + lying + byteArrayOf(0xFF.toByte(), 1, 0, 0, 0, 0))
    }

    @Test
    fun `a path deeper than 1024 segments is corrupt`() {
        val deep = (0..ArchiveListingCodec.MAX_PATH_DEPTH).joinToString("/") { "d" }
        assertCorrupt("too deep", ArchiveListingTestWriter.encode(listOf(ArchiveListingTestWriter.record(0, deep))))
        val justUnder = (1 until ArchiveListingCodec.MAX_PATH_DEPTH).joinToString("/") { "d" }
        assertEquals(justUnder, decode(ArchiveListingTestWriter.encode(listOf(ArchiveListingTestWriter.record(0, justUnder)))).records.single().path)
    }

    @Test
    fun `an unknown tag, kind, flag bit or partial value is corrupt`() {
        val one = ArchiveListingTestWriter.encode(listOf(ArchiveListingTestWriter.record(0, "a")))
        val badTag = one.copyOf().also { it[4] = 0x02 }
        assertCorrupt("tag", badTag)
        // kind sits after tag(1) ordinal(4) len(4) path(1) at offset 4 + 10.
        val badKind = one.copyOf().also { it[4 + 10] = 9 }
        assertCorrupt("kind", badKind)
        val badFlags = one.copyOf().also { it[4 + 11] = 0x80.toByte() }
        assertCorrupt("flags", badFlags)
        val badPartial = one.copyOf().also { it[it.size - 1] = 2 }
        assertCorrupt("partial", badPartial)
    }

    @Test
    fun `a truncated record is corrupt, never an exception of another kind`() {
        val one = ArchiveListingTestWriter.encode(listOf(ArchiveListingTestWriter.record(0, "abc", linkTarget = "t", kind = ArchiveEntryInfo.KIND_HARDLINK)))
        for (cut in 5 until one.size - 6) {
            assertCorrupt("cut at $cut", one.copyOf(cut))
        }
    }

    @Test
    fun `a lossy record truncated inside its own raw path field is corrupt at every cut, including a claimed length past the end`() {
        val one = ArchiveListingTestWriter.encode(
            listOf(ArchiveListingTestWriter.record(0, "caf�.txt", nameLossy = true, rawPathBytes = byteArrayOf(0x63, 0x61, 0x66, 0x82.toByte(), 0x2E, 0x74, 0x78, 0x74))),
        )
        for (cut in 5 until one.size - 6) {
            assertCorrupt("cut at $cut", one.copyOf(cut))
        }
        // Trailer(6) + the 8 raw path bytes + their own 4-byte length prefix.
        val lying = one.copyOf()
        ByteBuffer.wrap(lying).order(ByteOrder.LITTLE_ENDIAN).putInt(one.size - 6 - 8 - 4, 1_000)
        assertCorrupt("raw path runs past the end", lying)
    }
}
