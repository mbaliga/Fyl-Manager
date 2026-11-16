package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Adversarial verification of the archive inspectors: fuzzing, resource bounds, spec facts. */
class ArchiveInspectorsVerifyTest {

    // ---- fixtures copied from the builder tests ----
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

    private fun paxRecord(k: String, v: String): String {
        val body = " $k=$v\n"; var len = body.length + 1
        while ((len.toString() + body).length != len) len = (len.toString() + body).length
        return len.toString() + body
    }

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

    private fun xzBytes(): ByteArray {
        val flags = byteArrayOf(0, 4)
        val crc = CRC32(); crc.update(flags)
        val b = B().raw(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0)).raw(flags).l32(crc.value)
        b.z(40) // pretend blocks
        b.raw(byteArrayOf(0, 2, 0x14, 0x64, 0x1E, 0xC8.toByte(), 0x01, 0)).l32(0) // index: 2 records + pad + crc
        b.l32(0).l32(2).raw(flags).s("YZ")
        return b.bytes()
    }

    private fun sevenZ(headerFirst: Int, extra: ByteArray = ByteArray(0)): ByteArray {
        val hdr = byteArrayOf(headerFirst.toByte()) + extra
        val tail = B().l64(0).l64(hdr.size.toLong()).l32(CRC32().also { it.update(hdr) }.value).bytes()
        val crc = CRC32(); crc.update(tail)
        return B().raw(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4)).l32(crc.value).raw(tail).raw(hdr).bytes()
    }

    private fun rar4(): ByteArray {
        val b = B().raw(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        b.l16(0x1234).u8(0x73).l16(0x0108).l16(13).z(6) // main header: solid + first volume
        val name = "a.txt"
        b.l16(0).u8(0x74).l16(0x8000).l16(32 + name.length) // flags: with ADD_SIZE present
        b.l32(4).l32(100).u8(2).l32(0).l32(0).u8(29).u8(0x33).l16(name.length).l32(0).s(name).raw(ByteArray(4))
        b.l16(0).u8(0x74).l16(0x80E0).l16(32 + 3).l32(0).l32(0).u8(2).l32(0).l32(0).u8(29).u8(0x30).l16(3).l32(0).s("dir")
        return b.bytes()
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

    private fun newcEntry(name: String, mode: Long, data: ByteArray): ByteArray {
        fun h(v: Long) = "%08X".format(v)
        val b = B().s("070701").s(h(1)).s(h(mode)).s(h(0)).s(h(0)).s(h(1)).s(h(0)).s(h(data.size.toLong())).s(h(0)).s(h(0)).s(h(0)).s(h(0)).s(h(name.length + 1L)).s(h(0))
        b.s(name).u8(0); while (b.size() % 4 != 0) b.u8(0)
        b.raw(data); while (b.size() % 4 != 0) b.u8(0)
        return b.bytes()
    }

    private fun cpioNewc(): ByteArray = B().raw(newcEntry("dir", 0x41ED, ByteArray(0))).raw(newcEntry("dir/f.txt", 0x81A4, "hello".toByteArray()))
        .raw(newcEntry("TRAILER!!!", 0, ByteArray(0))).bytes()

    private fun squash(): ByteArray {
        val b = B().s("hsqs").l32(42).l32(1_700_000_000L).l32(131072).l32(3).l16(4).l16(17).l16(0x0C0 or 0x800).l16(2).l16(4).l16(0).l64(0x1234).l64(8192).z(40)
        return b.bytes()
    }

    private fun tarBytes(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tarHeader("dir/", 0, '5'))
        o.write(tarHeader("dir/hello.txt", 5, '0')); o.write("hello".toByteArray()); o.write(ByteArray(507))
        o.write(tarHeader("link", 0, '2'))
        o.write(ByteArray(1024))
        return o.toByteArray()
    }

    // ---------------------------------------------------------------- harness
    private class Counting(private val inner: ByteSource) : ByteSource {
        var requested = 0L; var returned = 0L; var calls = 0
        override val size: Long get() = inner.size
        override fun read(offset: Long, length: Int): ByteArray {
            calls++
            if (length > 0) requested += length
            val r = inner.read(offset, length)
            returned += r.size
            return r
        }
    }

    /** Periodic synthetic source of a huge size: byte at off = block[off % block.size]. */
    private class Periodic(val block: ByteArray, override val size: Long) : ByteSource {
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
            val n = minOf(length.toLong(), size - offset).toInt()
            val out = ByteArray(n)
            for (i in 0 until n) out[i] = block[((offset + i) % block.size).toInt()]
            return out
        }
    }

    private val failures = ArrayList<String>()
    private var runs = 0

    private fun check(tag: String, insp: FormatInspector, src: ByteSource, name: String?, maxBytes: Long = FormatInspector.MAX_READ_BYTES.toLong()) {
        runs++
        val c = Counting(src)
        val t0 = System.nanoTime()
        try { insp.inspect(c, name) } catch (t: Throwable) {
            if (failures.size < 60) failures += "$tag: EXCEPTION ${t.javaClass.simpleName}: ${t.message}"
            return
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (c.requested > maxBytes && failures.size < 60) failures += "$tag: requested ${c.requested} bytes > $maxBytes"
        if (c.calls > 20000 && failures.size < 60) failures += "$tag: ${c.calls} read calls"
        if (ms > 2000 && failures.size < 60) failures += "$tag: took $ms ms"
    }

    private class Fx(val label: String, val insp: FormatInspector, val data: ByteArray, val name: String?, val magic: ByteArray,
                     val hot: List<IntRange>? = null)

    private fun fixtures(): List<Fx> {
        val isoB = iso()
        val z = jarBytes()
        return listOf(
            Fx("zip", ArchiveZipInspector, z, "a.jar", "PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1)),
            Fx("zip64", ArchiveZipInspector, zip64Bytes(), "a.zip", "PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1)),
            Fx("tar", ArchiveTarInspector, tarBytes(), "a.tar", ByteArray(0)),
            Fx("gz", ArchiveGzInspector, gzBytes(tarBytes(), "n.tar"), "a.gz", byteArrayOf(0x1F, 0x8B.toByte(), 8)),
            Fx("bz2", ArchiveBz2Inspector, B().s("BZh9").raw(byteArrayOf(0x31, 0x41, 0x59, 0x26, 0x53, 0x59)).z(20).bytes(), null, "BZh9".toByteArray()),
            Fx("xz", ArchiveXzInspector, xzBytes(), null, byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0)),
            Fx("zstd", ArchiveZstdInspector, B().l32(0xFD2FB528L).u8(0x85).u8(0x3C).u8(5).l32(1000).z(20).bytes(), null, byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())),
            Fx("7z", Archive7zInspector, sevenZ(0x17, byteArrayOf(6, 0, 1, 1, 0x24, 6, 0xF1.toByte(), 7, 1, 0)), null, byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)),
            Fx("rar4", ArchiveRarInspector, rar4(), null, byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)),
            Fx("rar5", ArchiveRarInspector, rar5(), null, byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)),
            Fx("cab", ArchiveCabInspector, cab(), null, "MSCF".toByteArray()),
            Fx("deb", ArchiveArInspector, debBytes(), "a.deb", "!<arch>\n".toByteArray()),
            Fx("rpm", ArchiveRpmInspector, rpm(), null, byteArrayOf(0xED.toByte(), 0xAB.toByte(), 0xEE.toByte(), 0xDB.toByte())),
            Fx("iso", ArchiveIsoInspector, isoB, null, ByteArray(0), listOf(32768 until 32768 + 1024, 32768 + 2048 until 32768 + 2048 + 128, 0 until 64)),
            Fx("cpio-newc", ArchiveCpioInspector, cpioNewc(), null, "070701".toByteArray()),
            Fx("cpio-odc", ArchiveCpioInspector, odc(), null, "070707".toByteArray()),
            Fx("squashfs", ArchiveSquashfsInspector, squash().copyOf(8192), null, "hsqs".toByteArray()),
            Fx("squashfs-be", ArchiveSquashfsInspector, squash().copyOf(8192).also { it[0] = 's'.code.toByte(); it[1] = 'q'.code.toByte(); it[2] = 's'.code.toByte(); it[3] = 'h'.code.toByte() }, null, "sqsh".toByteArray()),
        )
    }

    private fun odc(): ByteArray {
        fun o(v: Long, n: Int) = "%0${n}o".format(v)
        fun ent(name: String, mode: Long, data: ByteArray) = B().s("070707").s(o(0, 6)).s(o(1, 6)).s(o(mode, 6)).s(o(0, 6)).s(o(0, 6)).s(o(1, 6)).s(o(0, 6)).s(o(0, 11))
            .s(o(name.length + 1L, 6)).s(o(data.size.toLong(), 11)).s(name).u8(0).raw(data).bytes()
        return B().raw(ent("a.txt", 33188, "abc".toByteArray())).raw(ent("TRAILER!!!", 0, ByteArray(0))).bytes()
    }

    private fun positions(f: Fx): List<Int> {
        val n = f.data.size
        if (f.hot != null) return f.hot.flatMap { r -> r.filter { it < n } }
        return (0 until minOf(n, 700)).toList() + (700 until n step 7)
    }

    @Test fun fuzzAll() {
        val rnd = Random(12345)
        for (f in fixtures()) {
            val d = f.data
            // every prefix
            val step = if (d.size > 3000) 97 else 1
            var n = 0
            while (n <= d.size) { check("${f.label} prefix $n", f.insp, ByteArraySource(d.copyOf(n)), f.name); n += if (n < 200) 1 else step }
            check("${f.label} full", f.insp, ByteArraySource(d), f.name)
            // single byte flips
            for (p in positions(f)) for (v in intArrayOf(0xFF, 0x00, 0x7F, 0x80)) {
                val c = d.copyOf(); c[p] = v.toByte()
                check("${f.label} flip@$p=$v", f.insp, ByteArraySource(c), f.name)
            }
            // 4 and 8 byte overwrites with hostile values, LE and BE
            for (p in positions(f)) {
                val vals = longArrayOf(0xFFFFFFFFL, 0x7FFFFFFFL, 0L, 0x80000000L)
                for (v in vals) {
                    val le = d.copyOf(); val be = d.copyOf()
                    for (k in 0 until 4) { if (p + k < d.size) { le[p + k] = (v shr (8 * k)).toByte(); be[p + k] = (v shr (8 * (3 - k))).toByte() } }
                    check("${f.label} le32@$p=$v", f.insp, ByteArraySource(le), f.name)
                    check("${f.label} be32@$p=$v", f.insp, ByteArraySource(be), f.name)
                }
                val big = d.copyOf(); for (k in 0 until 8) if (p + k < d.size) big[p + k] = if (k == 7) 0x7F else 0xFF.toByte()
                check("${f.label} i64max@$p", f.insp, ByteArraySource(big), f.name)
                val big2 = d.copyOf(); for (k in 0 until 8) if (p + k < d.size) big2[p + k] = 0xFF.toByte()
                check("${f.label} ff64@$p", f.insp, ByteArraySource(big2), f.name)
            }
            // zero / FF buffers
            for (sz in intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16, 20, 22, 31, 32, 33, 40, 47, 48, 59, 60, 63, 64, 96, 100, 110, 127, 255, 511, 512, 513, 1024, 2048, 4096, 33000, 40000, 70000)) {
                check("${f.label} zeros $sz", f.insp, ByteArraySource(ByteArray(sz)), f.name)
                check("${f.label} ff $sz", f.insp, ByteArraySource(ByteArray(sz) { -1 }), f.name)
                // random after magic
                for (rep in 0 until 3) {
                    val b = ByteArray(maxOf(sz, f.magic.size)); rnd.nextBytes(b); f.magic.copyInto(b, 0)
                    check("${f.label} magic+random $sz#$rep", f.insp, ByteArraySource(b), f.name)
                }
            }
            // magic then zeros / FFs
            for (sz in intArrayOf(64, 512, 5000, 70000)) {
                val z = ByteArray(sz); f.magic.copyInto(z, 0)
                check("${f.label} magic+zeros $sz", f.insp, ByteArraySource(z), f.name)
                val ff = ByteArray(sz) { -1 }; f.magic.copyInto(ff, 0)
                check("${f.label} magic+ff $sz", f.insp, ByteArraySource(ff), f.name)
            }
        }
        // every inspector over every fixture (cross-format garbage must not throw nor be claimed)
        for (f in fixtures()) for (i in ArchiveInspectors.all) check("cross ${f.label}->${i.javaClass.simpleName}", i, ByteArraySource(f.data), f.name)
        println("fuzz runs: $runs, failures: ${failures.size}")
        assertTrue("FUZZ FAILURES (${failures.size}):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ---------------------------------------------------------------- huge-source bounds (a 50 GB file must be cheap)
    private fun hugeCheck(label: String, insp: FormatInspector, blk: ByteArray, name: String? = null, size: Long = 50L * 1024 * 1024 * 1024) {
        failures.clear()
        check(label, insp, Periodic(blk, size), name)
        assertTrue("HUGE $label: " + failures.joinToString("; "), failures.isEmpty())
    }

    @Test fun hugeTarOfPaxGlobalHeaders() {
        val g = tarHeader("g", 0, 'g')
        hugeCheck("tar g-headers", ArchiveTarInspector, g)
    }
    @Test fun hugeTarOfPaxExtendedHeaders() {
        val rec = paxRecord("comment", "x").toByteArray()
        val blk = B().raw(tarHeader("x", 65536, 'x')).raw(ByteArray(65536)).bytes()
        hugeCheck("tar x-headers 64K", ArchiveTarInspector, blk)
    }
    @Test fun hugeArOfLongNameTables() {
        val data = ByteArray(262144)
        val blk = B().sp("//", 16).sp("0", 12).sp("0", 6).sp("0", 6).sp("0", 8).sp("262144", 10).s("`\n").raw(data).bytes()
        val src = object : ByteSource {
            val p = Periodic(blk, 50L * 1024 * 1024 * 1024); val magic = "!<arch>\n".toByteArray()
            override val size get() = p.size + 8
            override fun read(offset: Long, length: Int): ByteArray {
                if (offset < 0 || length <= 0 || offset >= size) return ByteArray(0)
                val out = ArrayList<Byte>(); var o = offset
                if (o < 8) { while (o < 8 && out.size < length) { out += magic[o.toInt()]; o++ } }
                if (out.size < length) out.addAll(p.read(o - 8, length - out.size).toList())
                return out.toByteArray()
            }
        }
        failures.clear()
        check("ar // members", ArchiveArInspector, src, null)
        assertTrue("HUGE ar: " + failures.joinToString("; "), failures.isEmpty())
    }
    @Test fun hugeCpio() {
        fun h(v: Long) = "%08X".format(v)
        val name = "a".repeat(4095)
        val b = B().s("070701").s(h(1)).s(h(0x81A4)).s(h(0)).s(h(0)).s(h(1)).s(h(0)).s(h(0)).s(h(0)).s(h(0)).s(h(0)).s(h(0)).s(h(4096)).s(h(0))
        b.s(name).u8(0); while (b.size() % 4 != 0) b.u8(0)
        hugeCheck("cpio 4K names", ArchiveCpioInspector, b.bytes())
    }
    @Test fun hugeRar4Rar5Zip7zCab() {
        // plausible-header-then-garbage on a giant file
        hugeCheck("rar5 repeating file headers", ArchiveRarInspector, rar5().copyOfRange(8, 8 + 12 + 24).let { rar5() })
        hugeCheck("zeros 50GB zip", ArchiveZipInspector, ByteArray(4096), "x.zip")
        hugeCheck("zeros 50GB tar", ArchiveTarInspector, ByteArray(4096))
        hugeCheck("7z start header only", Archive7zInspector, sevenZ(1, ByteArray(8)))
    }

    // ---------------------------------------------------------------- spec facts
    private fun r(i: FormatInspector, b: ByteArray, n: String? = null) = i.inspect(ByteArraySource(b), n)!!
    private fun InspectionResult.f(l: String) = facts.firstOrNull { it.label == l }?.value

    @Test fun squashfsFlagBitsPerSpec() {
        // squashfs_fs.h: 0x100 UNCOMPRESSED_XATTRS, 0x200 NO_XATTRS, 0x400 COMPRESSOR_OPTIONS, 0x800 UNCOMPRESSED_IDS
        fun flags(v: Int): String = r(ArchiveSquashfsInspector, B().s("hsqs").l32(1).l32(0).l32(131072).l32(0).l16(1).l16(17).l16(v).l16(1).l16(4).l16(0).l64(0).l64(4096).z(40).bytes().copyOf(4096)).f("Flags")!!
        assertEquals("compressor options present", flags(0x400))
        assertFalse("0x800 is UNCOMPRESSED_IDS, not compressor options: " + flags(0x800), flags(0x800).contains("compressor options"))
    }

    @Test fun rpmValueOffsetNearIntMaxIsNotFabricated() {
        val b = rpm()
        // main header index starts at 96 + sig(16+16+4) padded to 8 -> locate tag 1009 entry and set its offset to 0x7FFFFFFE
        val mainAt = 96 + ((16 + 16 + 4 + 7) / 8) * 8
        var patched = false
        val c = b.copyOf()
        for (e in 0 until 6) {
            val o = mainAt + 16 + e * 16
            val tag = ((c[o].toInt() and 0xFF) shl 24) or ((c[o + 1].toInt() and 0xFF) shl 16) or ((c[o + 2].toInt() and 0xFF) shl 8) or (c[o + 3].toInt() and 0xFF)
            if (tag == 1009) { c[o + 8] = 0x7F; c[o + 9] = 0xFF.toByte(); c[o + 10] = 0xFF.toByte(); c[o + 11] = 0xFE.toByte(); patched = true }
        }
        assertTrue(patched)
        val res = r(ArchiveRpmInspector, c)
        assertNull("out-of-range int32 offset must not yield a value: " + res.f("Installed size"), res.f("Installed size"))
    }

    @Test fun sevenZipOverflowingHeaderRangeIsRejected() {
        val s = sevenZ(0x01, ByteArray(8)).copyOf()
        // offset = 2^62, size = 2^62 : 32 + off + len overflows to a negative number
        for (k in 0 until 8) { s[12 + k] = 0; s[20 + k] = 0 }
        s[19] = 0x40; s[27] = 0x40
        val crc = CRC32(); crc.update(s, 12, 20)
        for (k in 0 until 4) s[8 + k] = (crc.value shr (8 * k)).toByte()
        val c = Counting(ByteArraySource(s))
        val res = ArchiveSevenProbe.run(c)
        assertTrue("reads beyond EOF: " + c.requested, res)
    }

    @Test fun xzConcatenatedStreamsDoNotClaimWholeFileSize() {
        val one = xzBytes()
        val two = one + one
        val res = r(ArchiveXzInspector, two)
        val u = res.f("Uncompressed size")
        assertTrue("size reported for last stream only yet unlabelled: $u / warnings ${res.warnings}", u == null || res.warnings.isNotEmpty())
    }

    @Test fun gzWithTrailingGarbageDoesNotReportTrailerFacts() {
        val g = gzBytes("hello".toByteArray()) + ByteArray(16)
        val res = r(ArchiveGzInspector, g)
        assertTrue("CRC/ISIZE taken from padding: ${res.f("CRC-32 (last member)")}", res.f("CRC-32 (last member)") == null || res.warnings.isNotEmpty())
    }

    @Test fun zstdHugeContentSizeNotMislabelled() {
        val b = B().l32(0xFD2FB528L).u8(0xC0).u8(0x3C).l64(-1L).z(20).bytes()
        val res = r(ArchiveZstdInspector, b)
        assertFalse("content size field present but reported as not recorded: ${res.f("Content size")}", res.f("Content size") == "not recorded")
    }

    @Test fun nonMatchingReturnsNull() {
        val txt = "hello world, this is plain text. ".repeat(300).toByteArray()
        for (i in ArchiveInspectors.all) assertNull(i.javaClass.simpleName, i.inspect(ByteArraySource(txt), "a.txt"))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(5000)
        for (i in ArchiveInspectors.all) assertNull(i.javaClass.simpleName, i.inspect(ByteArraySource(png), "a.png"))
    }

    @Test fun registryAndIds() {
        val all = ArchiveInspectors.all
        assertEquals(14, all.size)
        assertEquals(all.size, all.map { it.javaClass }.toSet().size)
        val ids = all.flatMap { it.formatIds }
        for (id in ids) assertTrue("id must be lowercase ext: $id", id == id.lowercase() && !id.contains('.') && !id.contains(' '))
        assertEquals(ids.size, ids.toSet().size)
        // every produced formatId is declared by its inspector
        for (f in fixtures()) { val res = f.insp.inspect(ByteArraySource(f.data), f.name); assertNotNull(f.label, res); assertTrue("${f.label} -> ${res!!.formatId}", res.formatId in f.insp.formatIds) }
    }
}

private object ArchiveSevenProbe {
    /** True when the inspector never asked for bytes past the end of the file. */
    fun run(c: ByteSource): Boolean {
        val probe = object : ByteSource {
            override val size get() = c.size
            var beyond = false
            override fun read(offset: Long, length: Int): ByteArray { if (offset >= c.size) beyond = true; return c.read(offset, length) }
        }
        Archive7zInspector.inspect(probe, null)
        return !probe.beyond
    }
}
