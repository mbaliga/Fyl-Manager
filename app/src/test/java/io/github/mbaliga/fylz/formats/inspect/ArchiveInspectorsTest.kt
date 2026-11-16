package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveInspectorsTest {

    // ---------------------------------------------------------------- helpers
    private class B {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = apply { o.write(v and 0xFF) }
        fun l16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun l32(v: Long) = apply { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        fun l64(v: Long) = apply { for (i in 0 until 8) u8((v shr (8 * i)).toInt()) }
        fun b16(v: Int) = apply { u8(v shr 8); u8(v) }
        fun b32(v: Long) = apply { for (i in 3 downTo 0) u8((v shr (8 * i)).toInt()) }
        fun s(t: String) = apply { o.write(t.toByteArray(Charsets.ISO_8859_1)) }
        fun z(n: Int) = apply { repeat(n) { u8(0) } }
        fun raw(b: ByteArray) = apply { o.write(b) }
        fun fixed(t: String, n: Int) = apply { val b = t.toByteArray(Charsets.ISO_8859_1); o.write(b, 0, minOf(n, b.size)); z(n - minOf(n, b.size)) }
        fun sp(t: String, n: Int) = apply { s(t.padEnd(n, ' ')) }
        fun size() = o.size()
        fun bytes(): ByteArray = o.toByteArray()
    }

    private fun insp(i: FormatInspector, b: ByteArray, name: String? = null) = i.inspect(ByteArraySource(b), name)
    private fun InspectionResult.fact(label: String) = facts.first { it.label == label }.value
    private fun InspectionResult.has(label: String) = facts.any { it.label == label }

    private fun noThrowPrefixes(i: FormatInspector, b: ByteArray, name: String? = null, step: Int = 1) {
        var n = 0
        while (n <= b.size) { insp(i, b.copyOf(n), name); n += if (n < 200) 1 else step }
        insp(i, b, name)
    }

    /** Patch bytes and make sure inspection returns promptly without throwing. */
    private fun hostile(i: FormatInspector, b: ByteArray, name: String? = null, vararg patches: Pair<Int, Long>) {
        val c = b.copyOf()
        for ((off, v) in patches) for (k in 0 until 4) c[off + k] = (v shr (8 * k)).toByte()
        val t = System.nanoTime()
        insp(i, c, name)
        assertTrue("took too long", (System.nanoTime() - t) < 5_000_000_000L)
    }
    private fun hostileBe(i: FormatInspector, b: ByteArray, off: Int, name: String? = null) {
        val c = b.copyOf(); for (k in 0 until 4) c[off + k] = 0xFF.toByte()
        insp(i, c, name)
    }

    // ---------------------------------------------------------------- zip
    private fun jarBytes(): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z ->
            z.setComment("test comment")
            val m = ZipEntry("META-INF/MANIFEST.MF"); m.time = 1_700_000_000_000L
            z.putNextEntry(m)
            z.write("Manifest-Version: 1.0\r\nMain-Class: com.example.App\r\nCreated-By: unit test\r\n\r\nName: x\r\nFoo: bar\r\n".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("com/")); z.closeEntry()
            val c = ZipEntry("com/App.class"); z.putNextEntry(c); z.write(ByteArray(300) { 7 }); z.closeEntry()
        }
        return o.toByteArray()
    }

    @Test fun zipJar() {
        val r = insp(ArchiveZipInspector, jarBytes(), "app.jar")!!
        assertEquals("jar", r.formatId)
        assertEquals("3", r.fact("Entries"))
        assertEquals("1", r.fact("Directories"))
        assertEquals("false", (r.has("Zip64") && r.fact("Zip64") == "yes").toString())
        assertEquals("com.example.App", r.fact("Main-Class"))
        assertEquals("unit test", r.fact("Created-By"))
        assertEquals("test comment", r.fact("Comment"))
        assertEquals("none", r.fact("Encryption"))
        assertTrue(r.fact("Flavour hints").contains("Java archive"))
        assertTrue(r.fact("com/App.class").startsWith("300 bytes"))
        assertTrue(r.fact("com/App.class").endsWith("Deflated"))
        assertEquals("directory", r.fact("com/"))
        assertEquals("false", r.warnings.isNotEmpty().toString())
        assertNotNull(r.text)
    }

    @Test fun zipApkAndMimetype() {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z ->
            val mt = ZipEntry("mimetype"); val d = "application/vnd.oasis.opendocument.text".toByteArray()
            mt.method = ZipEntry.STORED; mt.size = d.size.toLong(); mt.compressedSize = d.size.toLong()
            val crc = CRC32(); crc.update(d); mt.crc = crc.value
            z.putNextEntry(mt); z.write(d); z.closeEntry()
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(10)); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(10)); z.closeEntry()
        }
        val r = insp(ArchiveZipInspector, o.toByteArray(), "x.apk")!!
        assertEquals("apk", r.formatId)
        assertTrue(r.fact("Flavour hints").contains("Android package"))
        assertEquals("application/vnd.oasis.opendocument.text", r.fact("mimetype"))
        assertEquals("Stored x1, Deflated x2", r.fact("Compression methods"))
    }

    private fun zip64Bytes(): ByteArray {
        val b = B()
        b.s("PK\u0003\u0004").l16(45).l16(0).l16(0).l16(0).l16(0).l32(0).l32(0xFFFFFFFFL).l32(0xFFFFFFFFL).l16(7).l16(20).s("big.bin").z(20)
        val cdOff = b.size()
        val cd = B()
        cd.s("PK\u0001\u0002").l16(45).l16(45).l16(0).l16(0).l16(0).l16(0).l32(0).l32(0xFFFFFFFFL).l32(0xFFFFFFFFL)
            .l16(7).l16(28).l16(0).l16(0).l16(0).l32(0).l32(0xFFFFFFFFL).s("big.bin")
            .l16(1).l16(24).l64(5_000_000_000L).l64(5_000_000_000L).l64(0)
        val cdb = cd.bytes()
        b.raw(cdb)
        val z64 = b.size()
        b.s("PK\u0006\u0006").l64(44).l16(45).l16(45).l32(0).l32(0).l64(1).l64(1).l64(cdb.size.toLong()).l64(cdOff.toLong())
        b.s("PK\u0006\u0007").l32(0).l64(z64.toLong()).l32(1)
        b.s("PK\u0005\u0006").l16(0).l16(0).l16(0xFFFF).l16(0xFFFF).l32(0xFFFFFFFFL).l32(0xFFFFFFFFL).l16(0)
        return b.bytes()
    }

    @Test fun zip64() {
        val r = insp(ArchiveZipInspector, zip64Bytes(), "big.zip")!!
        assertEquals("yes", r.fact("Zip64"))
        assertEquals("1", r.fact("Entries"))
        assertEquals("5000000000 bytes", r.fact("Total uncompressed size"))
        assertEquals("5000000000 bytes", r.fact("Total compressed size"))
        assertEquals("false", r.warnings.isNotEmpty().toString())
    }

    @Test fun zipEncryptedFlag() {
        val z = jarBytes().copyOf()
        // set the encryption bit in the first central directory header
        var p = -1
        for (i in 0 until z.size - 4) if (z[i].toInt() == 0x50 && z[i + 1].toInt() == 0x4B && z[i + 2].toInt() == 1 && z[i + 3].toInt() == 2) { p = i; break }
        z[p + 8] = (z[p + 8].toInt() or 1).toByte()
        val r = insp(ArchiveZipInspector, z, "a.zip")!!
        assertEquals("1 entries encrypted", r.fact("Encryption"))
    }

    @Test fun zipEmpty() {
        val b = B().s("PK\u0005\u0006").z(18).bytes()
        val r = insp(ArchiveZipInspector, b, "e.zip")!!
        assertEquals("0", r.fact("Entries"))
    }

    @Test fun zipRobust() {
        val z = jarBytes()
        noThrowPrefixes(ArchiveZipInspector, z, "a.zip")
        noThrowPrefixes(ArchiveZipInspector, zip64Bytes(), "a.zip")
        val eocd = z.size - 22 - "test comment".length
        hostile(ArchiveZipInspector, z, "a.zip", (eocd + 12) to 0xFFFFFFFFL, (eocd + 16) to 0xFFFFFFFFL)
        hostile(ArchiveZipInspector, z, "a.zip", (eocd + 12) to 0xFFFFFFFFL)
        hostile(ArchiveZipInspector, z, "a.zip", (eocd + 16) to 0xFFFFFFFFL)
        val z64 = zip64Bytes()
        val rec = z64.size - 22 - 20 - 56
        for (o in listOf(32, 40, 48)) hostile(ArchiveZipInspector, z64, "a.zip", (rec + o) to 0xFFFFFFFFL, (rec + o + 4) to 0xFFFFFFFFL)
        hostile(ArchiveZipInspector, z64, "a.zip", (z64.size - 22 - 20 + 8) to 0xFFFFFFFFL)
        // hostile name / extra lengths inside the central directory
        var p = -1
        for (i in 0 until z.size - 4) if (z[i].toInt() == 0x50 && z[i + 1].toInt() == 0x4B && z[i + 2].toInt() == 1 && z[i + 3].toInt() == 2) { p = i; break }
        val c = z.copyOf(); c[p + 28] = -1; c[p + 29] = -1; c[p + 30] = -1; c[p + 31] = -1
        insp(ArchiveZipInspector, c, "a.zip")
        assertNull(insp(ArchiveZipInspector, ByteArray(100), "a.zip"))
    }

    // ---------------------------------------------------------------- tar
    private fun tarHeader(name: String, size: Long, type: Char, magic: String = "ustar\u000000", prefix: String = ""): ByteArray {
        val h = ByteArray(512)
        fun put(o: Int, s: String) { s.toByteArray(Charsets.ISO_8859_1).copyInto(h, o) }
        put(0, name); put(100, "0000644\u0000"); put(108, "0000000\u0000"); put(116, "0000000\u0000")
        put(124, "%011o\u0000".format(size)); put(136, "%011o\u0000".format(1_700_000_000L)); put(148, "        ")
        h[156] = type.code.toByte(); put(257, magic); put(345, prefix)
        var sum = 0; for (x in h) sum += x.toInt() and 0xFF
        put(148, "%06o\u0000 ".format(sum))
        return h
    }
    private fun tarBytes(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tarHeader("dir/", 0, '5'))
        o.write(tarHeader("dir/hello.txt", 5, '0')); o.write("hello".toByteArray()); o.write(ByteArray(507))
        o.write(tarHeader("link", 0, '2'))
        o.write(ByteArray(1024))
        return o.toByteArray()
    }
    private fun paxRecord(k: String, v: String): String {
        val body = " $k=$v\n"; var len = body.length + 1
        while ((len.toString() + body).length != len) len = (len.toString() + body).length
        return len.toString() + body
    }

    @Test fun tarBasic() {
        val r = insp(ArchiveTarInspector, tarBytes(), "a.tar")!!
        assertEquals("POSIX ustar", r.fact("Format variant"))
        assertEquals("3", r.fact("Entries"))
        assertEquals("5 bytes", r.fact("Total file data"))
        assertEquals("file, 5 bytes, 2023-11-14T22:13:20Z", r.fact("dir/hello.txt"))
        assertEquals("directory, 0 bytes, 2023-11-14T22:13:20Z", r.fact("dir/"))
        assertEquals("false", r.warnings.isNotEmpty().toString())
        assertTrue(r.text!!.contains("dir/hello.txt"))
    }

    @Test fun tarGnuAndPaxAndPrefix() {
        val g = B().raw(tarHeader("a.txt", 0, '0', "ustar  \u0000")).z(1024).bytes()
        assertTrue(insp(ArchiveTarInspector, g)!!.fact("Format variant").startsWith("GNU tar"))
        val v7 = B().raw(tarHeader("old.txt", 0, '0', "")).z(1024).bytes()
        assertTrue(insp(ArchiveTarInspector, v7)!!.fact("Format variant").startsWith("V7"))
        val pre = B().raw(tarHeader("f.txt", 0, '0', prefix = "some/deep/dir")).z(1024).bytes()
        assertTrue(insp(ArchiveTarInspector, pre)!!.has("some/deep/dir/f.txt"))
        val rec = paxRecord("path", "pax/very/long/name.txt").toByteArray()
        val o = ByteArrayOutputStream()
        o.write(tarHeader("PaxHeader", rec.size.toLong(), 'x')); o.write(rec); o.write(ByteArray(512 - rec.size))
        o.write(tarHeader("short", 0, '0')); o.write(ByteArray(1024))
        val r = insp(ArchiveTarInspector, o.toByteArray())!!
        assertTrue(r.has("pax/very/long/name.txt"))
        assertTrue(r.fact("Format variant").contains("pax"))
        assertEquals("1", r.fact("Entries"))
        assertNull(insp(ArchiveTarInspector, ByteArray(2048)))
        assertNull(insp(ArchiveTarInspector, ByteArray(600) { 'a'.code.toByte() }))
    }

    @Test fun tarRobust() {
        val t = tarBytes()
        noThrowPrefixes(ArchiveTarInspector, t, "a.tar")
        // huge size field (octal 77777777777 -> 8 GiB) and base-256 max
        val c = t.copyOf(); "77777777777\u0000".toByteArray().copyInto(c, 124)
        var sum = 0; for (i in 0 until 512) sum += if (i in 148..155) 0x20 else (c[i].toInt() and 0xFF)
        "%06o\u0000 ".format(sum).toByteArray().copyInto(c, 148)
        val r = insp(ArchiveTarInspector, c)
        assertNotNull(r)
        val d = t.copyOf(); d[124] = 0xFF.toByte(); for (i in 1 until 12) d[124 + i] = 0xFF.toByte()
        insp(ArchiveTarInspector, d)
        val long = tarBytes(); hostile(ArchiveTarInspector, long, null)
    }

    // ---------------------------------------------------------------- gz
    private fun rawDeflate(d: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true); def.setInput(d); def.finish()
        val o = ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!def.finished()) { val n = def.deflate(buf); o.write(buf, 0, n) }
        def.end(); return o.toByteArray()
    }
    private fun gzBytes(payload: ByteArray, name: String? = "hello.txt"): ByteArray {
        val crc = CRC32(); crc.update(payload)
        val b = B().u8(0x1F).u8(0x8B).u8(8).u8(if (name != null) 8 else 0).l32(1_700_000_000L).u8(2).u8(3)
        if (name != null) b.s(name).u8(0)
        b.raw(rawDeflate(payload)).l32(crc.value).l32(payload.size.toLong())
        return b.bytes()
    }

    @Test fun gzHeader() {
        val r = insp(ArchiveGzInspector, gzBytes("hello".toByteArray()), "hello.txt.gz")!!
        assertEquals("deflate", r.fact("Compression method"))
        assertEquals("2023-11-14T22:13:20Z", r.fact("Modification time"))
        assertEquals("Unix", r.fact("Operating system"))
        assertEquals("hello.txt", r.fact("Original name"))
        assertEquals("5", r.fact("Uncompressed size mod 2^32 (last member)"))
        assertEquals("maximum compression (slowest)", r.fact("Extra flags"))
        val c = CRC32(); c.update("hello".toByteArray())
        assertEquals("%08x".format(c.value), r.fact("CRC-32 (last member)"))
    }

    @Test fun gzContainingTar() {
        val r = insp(ArchiveGzInspector, gzBytes(tarBytes(), null), "x.tar.gz")!!
        assertEquals("tar archive", r.fact("Payload"))
        assertTrue(r.summary!!.contains("tar"))
    }

    @Test fun gzRobust() {
        val g = gzBytes("hello".toByteArray())
        noThrowPrefixes(ArchiveGzInspector, g)
        val c = g.copyOf(); c[3] = 0x1F; // all flags incl. extra with huge length
        c[10] = -1; c[11] = -1
        insp(ArchiveGzInspector, c)
        assertNull(insp(ArchiveGzInspector, byteArrayOf(1, 2, 3)))
    }

    // ---------------------------------------------------------------- bz2
    @Test fun bz2() {
        val b = B().s("BZh9").raw(byteArrayOf(0x31, 0x41, 0x59, 0x26, 0x53, 0x59)).z(20).bytes()
        val r = insp(ArchiveBz2Inspector, b)!!
        assertEquals("9", r.fact("Block size level"))
        assertEquals("900000 bytes", r.fact("Maximum block size"))
        assertTrue(r.fact("First block").startsWith("data block"))
        noThrowPrefixes(ArchiveBz2Inspector, b)
        val e = B().s("BZh1").raw(byteArrayOf(0x17, 0x72, 0x45, 0x38, 0x50, 0x90.toByte())).z(4).bytes()
        assertTrue(insp(ArchiveBz2Inspector, e)!!.fact("First block").startsWith("end of stream"))
        assertNull(insp(ArchiveBz2Inspector, B().s("BZh0").z(10).bytes()))
        assertEquals("unexpected", if (insp(ArchiveBz2Inspector, B().s("BZh5").z(10).bytes())!!.warnings.isNotEmpty()) "unexpected" else "none")
    }

    // ---------------------------------------------------------------- xz
    private fun xzBytes(): ByteArray {
        val flags = byteArrayOf(0, 4)
        val crc = CRC32(); crc.update(flags)
        val b = B().raw(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0)).raw(flags).l32(crc.value)
        b.z(40) // pretend blocks
        b.raw(byteArrayOf(0, 2, 0x14, 0x64, 0x14, 0xC8.toByte(), 0x01, 0)).l32(0) // index: 2 records + pad + crc
        b.l32(0).l32(2).raw(flags).s("YZ")
        return b.bytes()
    }
    @Test fun xz() {
        val r = insp(ArchiveXzInspector, xzBytes())!!
        assertEquals("CRC64", r.fact("Integrity check"))
        assertEquals("valid", r.fact("Header CRC32"))
        assertEquals("2", r.fact("Blocks (index records)"))
        assertEquals("300 bytes", r.fact("Uncompressed size"))
        assertEquals("40 bytes", r.fact("Sum of unpadded block sizes"))
        assertEquals("false", r.warnings.isNotEmpty().toString())
        noThrowPrefixes(ArchiveXzInspector, xzBytes())
        val x = xzBytes()
        hostile(ArchiveXzInspector, x, null, (x.size - 8) to 0xFFFFFFFFL)
        val y = x.copyOf(); y[y.size - 12 + 4] = -1; y[y.size - 12 + 5] = -1; y[y.size - 12 + 6] = -1; y[y.size - 12 + 7] = -1
        insp(ArchiveXzInspector, y)
        val z = x.copyOf(); z[52] = 0xFF.toByte() // count vli with continuation bytes
        for (k in 53..59) z[k] = 0xFF.toByte()
        insp(ArchiveXzInspector, z)
    }

    // ---------------------------------------------------------------- zstd
    @Test fun zstd() {
        val b = B().l32(0xFD2FB528L).u8(0x85).u8(0x3C).u8(5).l32(1000).z(20).bytes()
        val r = insp(ArchiveZstdInspector, b)!!
        assertEquals("196608 bytes", r.fact("Window size"))
        assertEquals("5", r.fact("Dictionary ID"))
        assertEquals("1000 bytes", r.fact("Content size"))
        assertEquals("yes (XXH64)", r.fact("Content checksum"))
        assertEquals("no", r.fact("Single segment"))
        val s = B().l32(0xFD2FB528L).u8(0x20).u8(200).z(10).bytes()
        val r2 = insp(ArchiveZstdInspector, s)!!
        assertEquals("200 bytes", r2.fact("Content size")); assertEquals("200 bytes", r2.fact("Window size"))
        assertEquals("none", r2.fact("Dictionary ID"))
        val w = B().l32(0xFD2FB528L).u8(0x40).u8(0x00).l16(100).z(5).bytes() // FCS flag 1 => +256, window exp 0 => 1 KiB
        val r3 = insp(ArchiveZstdInspector, w)!!
        assertEquals("356 bytes", r3.fact("Content size")); assertEquals("1024 bytes", r3.fact("Window size"))
        val sk = B().l32(0x184D2A50L).l32(0xFFFFFFFFL).z(4).bytes()
        assertEquals("4294967295", insp(ArchiveZstdInspector, sk)!!.fact("Skippable frame data size"))
        noThrowPrefixes(ArchiveZstdInspector, b)
        hostile(ArchiveZstdInspector, b, null, 5 to 0xFFFFFFFFL, 9 to 0xFFFFFFFFL)
        assertNull(insp(ArchiveZstdInspector, ByteArray(50)))
    }

    // ---------------------------------------------------------------- 7z
    private fun sevenZ(headerFirst: Int, extra: ByteArray = ByteArray(0)): ByteArray {
        val hdr = byteArrayOf(headerFirst.toByte()) + extra
        val tail = B().l64(0).l64(hdr.size.toLong()).l32(CRC32().also { it.update(hdr) }.value).bytes()
        val crc = CRC32(); crc.update(tail)
        return B().raw(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4)).l32(crc.value).raw(tail).raw(hdr).bytes()
    }
    @Test fun sevenZip() {
        val r = insp(Archive7zInspector, sevenZ(0x01, ByteArray(8)))!!
        assertEquals("0.4", r.fact("Format version"))
        assertEquals("valid", r.fact("Start header CRC"))
        assertEquals("valid", r.fact("Next header CRC"))
        assertEquals("0", r.fact("Next header offset"))
        assertEquals("9", r.fact("Next header size"))
        assertTrue(r.fact("Header type").startsWith("plain"))
        val e = insp(Archive7zInspector, sevenZ(0x17, byteArrayOf(6, 0, 1, 1, 0x24, 6, 0xF1.toByte(), 7, 1, 0)))!!
        assertTrue(e.fact("Header encryption").startsWith("likely"))
        assertTrue(e.summary!!.contains("encrypted"))
        val n = insp(Archive7zInspector, sevenZ(0x17, byteArrayOf(6, 0, 1, 1, 0x21, 0)))!!
        assertTrue(n.fact("Header encryption").startsWith("no AES"))
        // empty archive
        val empty = B().raw(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4)).l32(CRC32().also { it.update(ByteArray(20)) }.value).z(20).bytes()
        assertEquals("empty archive (no header)", insp(Archive7zInspector, empty)!!.fact("Contents"))
        val s = sevenZ(0x01, ByteArray(8))
        noThrowPrefixes(Archive7zInspector, s)
        val h = s.copyOf(); for (k in 20 until 28) h[k] = 0xFF.toByte()
        assertTrue(insp(Archive7zInspector, h)!!.warnings.isNotEmpty())
        val h2 = s.copyOf(); for (k in 12 until 20) h2[k] = 0xFF.toByte()
        insp(Archive7zInspector, h2)
    }

    // ---------------------------------------------------------------- rar
    private fun rar4(): ByteArray {
        val b = B().raw(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        b.l16(0x1234).u8(0x73).l16(0x0108).l16(13).z(6) // main header: solid + first volume
        val name = "a.txt"
        b.l16(0).u8(0x74).l16(0x8000).l16(32 + name.length) // flags: with ADD_SIZE present
        b.l32(4).l32(100).u8(2).l32(0).l32(0).u8(29).u8(0x33).l16(name.length).l32(0).s(name).raw(ByteArray(4))
        b.l16(0).u8(0x74).l16(0x80E0).l16(32 + 3).l32(0).l32(0).u8(2).l32(0).l32(0).u8(29).u8(0x30).l16(3).l32(0).s("dir")
        return b.bytes()
    }
    @Test fun rar4Test() {
        val r = insp(ArchiveRarInspector, rar4())!!
        assertTrue(r.fact("Version").contains("RAR4"))
        assertEquals("solid, first volume", r.fact("Main header flags"))
        assertEquals("1", r.fact("Files"))
        assertEquals("1", r.fact("Directories"))
        assertEquals("100 bytes unpacked", r.fact("a.txt"))
        assertEquals("directory", r.fact("dir"))
        noThrowPrefixes(ArchiveRarInspector, rar4())
        val enc = B().raw(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)).l16(0).u8(0x73).l16(0x0080).l16(13).z(6).bytes()
        assertTrue(insp(ArchiveRarInspector, enc)!!.summary!!.contains("encrypted"))
        hostile(ArchiveRarInspector, rar4(), null, 7 + 13 + 7 to 0xFFFFFFFFL)
    }
    private fun rar5(): ByteArray {
        val b = B().raw(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        b.l32(0).u8(3).u8(1).u8(0).u8(0x04) // main: size 3, type 1, hflags 0, archive flags solid
        val name = "doc/readme.txt"
        val body = B().u8(2).u8(2).u8(5) /*type, hflags=data size, datasize 5*/ .u8(0).u8(10).u8(0) /*fflags, unpacked 10, attrs*/ .u8(0).u8(0).u8(name.length).s(name).bytes()
        b.l32(0).u8(body.size).raw(body).z(5)
        b.l32(0).u8(3).u8(5).u8(0).u8(0) // end of archive
        return b.bytes()
    }
    @Test fun rar5Test() {
        val r = insp(ArchiveRarInspector, rar5())!!
        assertTrue(r.fact("Version").contains("RAR5"))
        assertEquals("solid", r.fact("Archive flags"))
        assertEquals("1", r.fact("Files"))
        assertEquals("10 bytes unpacked", r.fact("doc/readme.txt"))
        assertEquals("no", r.fact("Headers encrypted"))
        noThrowPrefixes(ArchiveRarInspector, rar5())
        val enc = B().raw(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)).l32(0).u8(5).u8(4).u8(0).z(3).bytes()
        assertEquals("yes", insp(ArchiveRarInspector, enc)!!.fact("Headers encrypted"))
        val h = rar5().copyOf(); h[12] = 0xFF.toByte(); h[13] = 0xFF.toByte(); h[14] = 0xFF.toByte(); h[15] = 0xFF.toByte()
        insp(ArchiveRarInspector, h)
        assertNull(insp(ArchiveRarInspector, B().s("Rar!\u001A\u0007\u0002").z(10).bytes()))
    }

    // ---------------------------------------------------------------- cab
    private fun cab(): ByteArray {
        val files = B().l32(1234).l32(0).l16(0).l16((2024 - 1980 shl 9) or (3 shl 5) or 15).l16((10 shl 11) or (30 shl 5)).l16(0x20).s("setup.exe").u8(0)
            .l32(10).l32(1234).l16(0).l16(0).l16(0).l16(0x20).s("readme.txt").u8(0).bytes()
        val coff = 36 + 8
        val total = coff + files.size + 10
        val b = B().s("MSCF").l32(0).l32(total.toLong()).l32(0).l32(coff.toLong()).l32(0).u8(3).u8(1).l16(1).l16(2).l16(0).l16(4242).l16(0)
        b.l32(0).l16(1).l16(1) // folder: data offset, 1 CFDATA, MSZIP
        b.raw(files).z(10)
        return b.bytes()
    }
    @Test fun cabTest() {
        val r = insp(ArchiveCabInspector, cab(), "a.cab")!!
        assertEquals("1.3", r.fact("Version"))
        assertEquals("2", r.fact("Files")); assertEquals("1", r.fact("Folders"))
        assertEquals("MSZIP", r.fact("First folder compression"))
        assertEquals("1244 bytes", r.fact("Total uncompressed size"))
        assertEquals("1234 bytes, 2024-03-15 10:30:00", r.fact("setup.exe"))
        assertEquals("4242", r.fact("Set ID"))
        assertEquals("false", r.warnings.isNotEmpty().toString())
        noThrowPrefixes(ArchiveCabInspector, cab())
        hostile(ArchiveCabInspector, cab(), null, 16 to 0xFFFFFFFFL)
        hostile(ArchiveCabInspector, cab(), null, 8 to 0xFFFFFFFFL, 28 to 0xFFFFFFFFL)
    }

    // ---------------------------------------------------------------- ar / deb
    private fun arMember(name: String, data: ByteArray): ByteArray {
        val b = B().sp(name, 16).sp("1700000000", 12).sp("0", 6).sp("0", 6).sp("100644", 8).sp(data.size.toString(), 10).s("`\n").raw(data)
        if (data.size % 2 == 1) b.s("\n")
        return b.bytes()
    }
    private fun gzip(d: ByteArray): ByteArray { val o = ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(o).use { it.write(d) }; return o.toByteArray() }
    private fun debBytes(): ByteArray {
        val ctl = "Package: hello\nVersion: 2.10-2\nArchitecture: amd64\nMaintainer: A Person <a@example.org>\nInstalled-Size: 280\nDescription: example greeting\n long text\n".toByteArray()
        val t = ByteArrayOutputStream()
        t.write(tarHeader("./", 0, '5')); t.write(tarHeader("./control", ctl.size.toLong(), '0')); t.write(ctl); t.write(ByteArray(512 - ctl.size % 512)); t.write(ByteArray(1024))
        val b = B().s("!<arch>\n").raw(arMember("debian-binary", "2.0\n".toByteArray()))
            .raw(arMember("control.tar.gz/", gzip(t.toByteArray()))).raw(arMember("data.tar.xz/", ByteArray(33)))
        return b.bytes()
    }
    @Test fun debTest() {
        val r = insp(ArchiveArInspector, debBytes(), "hello.deb")!!
        assertEquals("deb", r.formatId)
        assertEquals("hello", r.fact("Package"))
        assertEquals("2.10-2", r.fact("Version"))
        assertEquals("amd64", r.fact("Architecture"))
        assertEquals("example greeting", r.fact("Description"))
        assertEquals("2.0", r.fact("deb format version"))
        assertEquals("3", r.fact("Members"))
        assertEquals("data.tar.xz", r.fact("Data archive"))
        assertTrue(r.summary!!.contains("hello 2.10-2"))
        noThrowPrefixes(ArchiveArInspector, debBytes())
    }
    @Test fun plainAr() {
        val b = B().s("!<arch>\n").raw(arMember("//", "very_long_member_name.o/\n".toByteArray()))
            .raw(arMember("/0", ByteArray(3))).raw(arMember("short.o/", ByteArray(4))).bytes()
        val r = insp(ArchiveArInspector, b, "lib.a")!!
        assertEquals("ar", r.formatId)
        assertEquals("2", r.fact("Members"))
        assertTrue(r.has("very_long_member_name.o"))
        assertTrue(r.has("short.o"))
        hostile(ArchiveArInspector, b, null, (8 + 48) to 0x39393939L)
        val big = B().s("!<arch>\n").sp("x/", 16).sp("0", 12).sp("0", 6).sp("0", 6).sp("0", 8).sp("9999999999", 10).s("`\n").bytes()
        assertTrue(insp(ArchiveArInspector, big)!!.warnings.isNotEmpty())
        assertNull(insp(ArchiveArInspector, ByteArray(20)))
    }

    // ---------------------------------------------------------------- rpm
    private fun rpm(): ByteArray {
        val store = B()
        val offs = HashMap<Int, Int>()
        fun str(tag: Int, v: String) { offs[tag] = store.size(); store.s(v).u8(0) }
        str(1000, "bash"); str(1001, "5.1.8"); str(1002, "2.fc35"); str(1004, "The GNU Bourne Again shell"); str(1022, "x86_64")
        val sizeOff = store.size(); store.b32(1_850_000L)
        val ver = ArrayList<IntArray>()
        for (t in listOf(1000, 1001, 1002, 1004, 1022)) ver += intArrayOf(t, if (t == 1004) 9 else 6, offs[t]!!, 1)
        ver += intArrayOf(1009, 4, sizeOff, 1)
        val main = B().raw(byteArrayOf(0x8E.toByte(), 0xAD.toByte(), 0xE8.toByte(), 1, 0, 0, 0, 0)).b32(ver.size.toLong()).b32(store.size().toLong())
        for (e in ver.sortedBy { it[0] }) main.b32(e[0].toLong()).b32(e[1].toLong()).b32(e[2].toLong()).b32(e[3].toLong())
        main.raw(store.bytes())
        val sig = B().raw(byteArrayOf(0x8E.toByte(), 0xAD.toByte(), 0xE8.toByte(), 1, 0, 0, 0, 0)).b32(1).b32(4).b32(1000).b32(4).b32(0).b32(1).b32(7)
        val b = B().raw(byteArrayOf(0xED.toByte(), 0xAB.toByte(), 0xEE.toByte(), 0xDB.toByte(), 3, 0)).b16(0).b16(1).fixed("bash-5.1.8-2.fc35", 66).b16(1).b16(5).z(16)
        b.raw(sig.bytes()); while (b.size() % 8 != 0) b.u8(0)
        b.raw(main.bytes())
        return b.bytes()
    }
    @Test fun rpmTest() {
        val r = insp(ArchiveRpmInspector, rpm(), "bash.rpm")!!
        assertEquals("bash", r.fact("Name")); assertEquals("5.1.8", r.fact("Version")); assertEquals("2.fc35", r.fact("Release"))
        assertEquals("x86_64", r.fact("Architecture"))
        assertEquals("1850000 bytes", r.fact("Installed size"))
        assertEquals("The GNU Bourne Again shell", r.fact("Summary"))
        assertEquals("binary", r.fact("Package type"))
        assertEquals("bash-5.1.8-2.fc35", r.fact("Lead name"))
        assertEquals("1 (Linux)", r.fact("Lead OS number"))
        assertEquals("1 (i386)", r.fact("Lead architecture number"))
        assertEquals("RPM package bash-5.1.8-2.fc35.x86_64", r.summary)
        noThrowPrefixes(ArchiveRpmInspector, rpm())
        val b = rpm()
        hostile(ArchiveRpmInspector, b, null, (96 + 8) to 0xFFFFFFFFL, (96 + 12) to 0xFFFFFFFFL)
        val mainAt = 96 + 16 + 16 + 4 + 4
        hostileBe(ArchiveRpmInspector, b, mainAt + 8)
        hostileBe(ArchiveRpmInspector, b, mainAt + 12)
        assertNull(insp(ArchiveRpmInspector, ByteArray(200)))
    }

    // ---------------------------------------------------------------- iso
    private fun iso(): ByteArray {
        val data = ByteArray(20 * 2048)
        fun put(o: Int, s: String, len: Int = s.length) { val t = s.padEnd(len, ' ').toByteArray(Charsets.ISO_8859_1); t.copyInto(data, o) }
        var p = 32768
        data[p] = 1; put(p + 1, "CD001"); data[p + 6] = 1
        put(p + 8, "LINUX", 32); put(p + 40, "MY_DISC", 32)
        B().l32(20).b32(20).bytes().copyInto(data, p + 80)
        B().l16(2048).b16(2048).bytes().copyInto(data, p + 128)
        put(p + 190, "SET", 128); put(p + 318, "ACME PUBLISHER", 128); put(p + 446, "PREP", 128); put(p + 574, "MKISOFS", 128)
        put(p + 813, "2024031510300000"); data[p + 829] = 4
        put(p + 830, "2024031511000000"); data[p + 846] = 0
        put(p + 847, "0000000000000000"); put(p + 864, "0000000000000000")
        data[p + 881] = 1
        p += 2048
        data[p] = 0; put(p + 1, "CD001"); data[p + 6] = 1; put(p + 7, "EL TORITO SPECIFICATION", 32)
        B().l32(19).bytes().copyInto(data, p + 71)
        p += 2048
        data[p] = 255.toByte(); put(p + 1, "CD001"); data[p + 6] = 1
        return data
    }
    @Test fun isoTest() {
        val r = insp(ArchiveIsoInspector, iso(), "d.iso")!!
        assertEquals("MY_DISC", r.fact("Volume identifier"))
        assertEquals("LINUX", r.fact("System identifier"))
        assertEquals("ACME PUBLISHER", r.fact("Publisher"))
        assertEquals("MKISOFS", r.fact("Application"))
        assertEquals("20 blocks", r.fact("Volume space size"))
        assertEquals("2048", r.fact("Logical block size"))
        assertEquals("40960 bytes", r.fact("Total size"))
        assertEquals("2024-03-15 10:30:00 (UTC+1h0m)", r.fact("Created"))
        assertEquals("2024-03-15 11:00:00 (UTC+0h0m)", r.fact("Modified"))
        assertEquals("not set", r.fact("Expires"))
        assertEquals("present (boot catalog at sector 19)", r.fact("El Torito boot record"))
        assertEquals("false", r.warnings.isNotEmpty().toString())
        val i = iso()
        noThrowPrefixes(ArchiveIsoInspector, i, null, 997)
        hostile(ArchiveIsoInspector, i, null, (32768 + 80) to 0xFFFFFFFFL, (32768 + 128) to 0xFFFFFFFFL)
        assertNull(insp(ArchiveIsoInspector, ByteArray(40000)))
    }

    // ---------------------------------------------------------------- cpio
    private fun newcEntry(name: String, mode: Long, data: ByteArray): ByteArray {
        fun h(v: Long) = "%08X".format(v)
        val b = B().s("070701").s(h(1)).s(h(mode)).s(h(0)).s(h(0)).s(h(1)).s(h(0)).s(h(data.size.toLong())).s(h(0)).s(h(0)).s(h(0)).s(h(0)).s(h(name.length + 1L)).s(h(0))
        b.s(name).u8(0); while (b.size() % 4 != 0) b.u8(0)
        b.raw(data); while (b.size() % 4 != 0) b.u8(0)
        return b.bytes()
    }
    private fun cpioNewc(): ByteArray = B().raw(newcEntry("dir", 0x41ED, ByteArray(0))).raw(newcEntry("dir/f.txt", 0x81A4, "hello".toByteArray()))
        .raw(newcEntry("TRAILER!!!", 0, ByteArray(0))).bytes()
    @Test fun cpioNewcTest() {
        val r = insp(ArchiveCpioInspector, cpioNewc(), "x.cpio")!!
        assertEquals("newc", r.fact("Variant"))
        assertEquals("2", r.fact("Entries"))
        assertEquals("1", r.fact("Regular files")); assertEquals("1", r.fact("Directories"))
        assertEquals("5 bytes", r.fact("Total file data"))
        assertEquals("file, 5 bytes", r.fact("dir/f.txt"))
        assertEquals("present", r.fact("Trailer"))
        noThrowPrefixes(ArchiveCpioInspector, cpioNewc())
        hostile(ArchiveCpioInspector, cpioNewc(), null)
        val c = cpioNewc().copyOf(); "FFFFFFFF".toByteArray().copyInto(c, 54)
        insp(ArchiveCpioInspector, c)
        val d = cpioNewc().copyOf(); "FFFFFFFF".toByteArray().copyInto(d, 94)
        insp(ArchiveCpioInspector, d)
    }
    @Test fun cpioOdcTest() {
        fun o(v: Long, n: Int) = "%0${n}o".format(v)
        fun ent(name: String, mode: Long, data: ByteArray) = B().s("070707").s(o(0, 6)).s(o(1, 6)).s(o(mode, 6)).s(o(0, 6)).s(o(0, 6)).s(o(1, 6)).s(o(0, 6)).s(o(0, 11))
            .s(o(name.length + 1L, 6)).s(o(data.size.toLong(), 11)).s(name).u8(0).raw(data).bytes()
        val b = B().raw(ent("a.txt", 33188, "abc".toByteArray())).raw(ent("TRAILER!!!", 0, ByteArray(0))).bytes()
        val r = insp(ArchiveCpioInspector, b)!!
        assertEquals("odc (portable ASCII)", r.fact("Variant"))
        assertEquals("1", r.fact("Entries")); assertEquals("3 bytes", r.fact("Total file data"))
        noThrowPrefixes(ArchiveCpioInspector, b)
        assertNull(insp(ArchiveCpioInspector, ByteArray(100)))
    }

    // ---------------------------------------------------------------- squashfs
    private fun squash(): ByteArray {
        val b = B().s("hsqs").l32(42).l32(1_700_000_000L).l32(131072).l32(3).l16(4).l16(17).l16(0x0C0 or 0x400).l16(2).l16(4).l16(0).l64(0x1234).l64(8192).z(40)
        return b.bytes()
    }
    @Test fun squashfsTest() {
        val r = insp(ArchiveSquashfsInspector, squash().copyOf(8192), "r.squashfs")!!
        assertEquals("4.0", r.fact("Version"))
        assertEquals("42", r.fact("Inode count"))
        assertEquals("xz", r.fact("Compression"))
        assertEquals("131072 bytes", r.fact("Block size"))
        assertEquals("3", r.fact("Fragment count"))
        assertEquals("2023-11-14T22:13:20Z", r.fact("Modification time"))
        assertEquals("duplicates removed, exportable, compressor options present", r.fact("Flags"))
        assertEquals("8192 bytes", r.fact("Bytes used"))
        assertEquals("false", r.warnings.isNotEmpty().toString())
        noThrowPrefixes(ArchiveSquashfsInspector, squash())
        hostile(ArchiveSquashfsInspector, squash(), null, 12 to 0xFFFFFFFFL, 4 to 0xFFFFFFFFL, 40 to 0xFFFFFFFFL, 44 to 0xFFFFFFFFL)
        assertNull(insp(ArchiveSquashfsInspector, ByteArray(100)))
    }

    // ---------------------------------------------------------------- registry
    @Test fun registry() {
        val ids = ArchiveInspectors.all.flatMap { it.formatIds }
        assertTrue(ids.containsAll(listOf("zip", "tar", "gz", "bz2", "xz", "zst", "7z", "rar", "cab", "deb", "rpm", "iso", "cpio", "squashfs", "jar", "apk")))
        for (i in ArchiveInspectors.all) {
            assertNull(i.inspect(ByteArraySource(ByteArray(0)), null))
            assertNull(i.inspect(ByteArraySource(ByteArray(5000) { 0x41 }), "x.bin"))
        }
    }
}
