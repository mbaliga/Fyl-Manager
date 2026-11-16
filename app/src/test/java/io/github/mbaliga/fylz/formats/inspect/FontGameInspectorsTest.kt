package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

private class W {
    val o = ByteArrayOutputStream()
    fun u8(v: Int) = apply { o.write(v and 0xFF) }
    fun be16(v: Int) = apply { u8(v shr 8); u8(v) }
    fun be32(v: Long) = apply { u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt()) }
    fun be64(v: Long) = apply { be32(v ushr 32); be32(v and 0xFFFFFFFFL) }
    fun le16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun le32(v: Long) = apply { u8(v.toInt()); u8((v shr 8).toInt()); u8((v shr 16).toInt()); u8((v shr 24).toInt()) }
    fun ascii(s: String) = apply { o.write(s.toByteArray(Charsets.ISO_8859_1)) }
    fun bytes(b: ByteArray) = apply { o.write(b) }
    fun pad(n: Int, v: Int = 0) = apply { repeat(n) { u8(v) } }
    fun padTo(n: Int) = apply { while (o.size() < n) u8(0) }
    fun fixedAscii(s: String, n: Int, fill: Int = 0) = apply { val b = s.toByteArray(Charsets.ISO_8859_1); o.write(b, 0, minOf(n, b.size)); pad(n - minOf(n, b.size), fill) }
    fun size() = o.size()
    fun done(): ByteArray = o.toByteArray()
}

private class HugeSource(private val prefix: ByteArray) : ByteSource {
    override val size: Long = 50L * 1024 * 1024 * 1024
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        for (i in 0 until length) { val p = offset + i; if (p < prefix.size) out[i] = prefix[p.toInt()] }
        return out
    }
}

private fun InspectionResult.f(label: String): String? = facts.firstOrNull { it.label == label }?.value
private fun insp(i: FormatInspector, b: ByteArray, name: String? = null) = i.inspect(ByteArraySource(b), name)

class FontGameInspectorsTest {

    // ---------------------------------------------------------------- fixtures

    private fun utf16(s: String) = s.toByteArray(Charsets.UTF_16BE)

    private fun nameTable(): ByteArray {
        class R(val p: Int, val e: Int, val l: Int, val id: Int, val data: ByteArray)
        val recs = listOf(
            R(1, 0, 0, 1, "MacName".toByteArray(Charsets.ISO_8859_1)),
            R(3, 1, 0x409, 0, utf16("Copyright 2024 Test")),
            R(3, 1, 0x409, 1, utf16("Test Sans")),
            R(3, 1, 0x409, 2, utf16("Bold")),
            R(3, 1, 0x409, 4, utf16("Test Sans Bold")),
            R(3, 1, 0x409, 5, utf16("Version 1.500")),
            R(3, 1, 0x409, 6, utf16("TestSans-Bold")),
            R(3, 1, 0x409, 9, utf16("Ann Designer")),
            R(3, 1, 0x409, 13, utf16("Free license")),
        )
        val w = W()
        w.be16(0).be16(recs.size).be16(6 + 12 * recs.size)
        var off = 0
        for (r in recs) { w.be16(r.p).be16(r.e).be16(r.l).be16(r.id).be16(r.data.size).be16(off); off += r.data.size }
        for (r in recs) w.bytes(r.data)
        return w.done()
    }

    private fun headTable(): ByteArray {
        val w = W()
        w.be32(0x00010000).be32(0x00018000).be32(0).be32(0x5F0F3CF5).be16(0).be16(2048)
        w.be64(3786912000L).be64(3786912000L + 86400)
        w.be16(-100).be16(-200).be16(1100).be16(900).be16(1).be16(8).be16(2).be16(0).be16(0)
        return w.done()
    }

    private fun tables(cff: Boolean, fvar: Boolean = false): List<Pair<String, ByteArray>> {
        val hhea = W().be32(0x00010000).be16(1900).be16(-500).be16(0).pad(26).done()
        val maxp = W().be32(0x00005000).be16(512).done()
        val os2 = W().be16(4).be16(500).be16(700).be16(5).be16(0).pad(48).ascii("TEST").be16(0x20).pad(32).done()
        val post = W().be32(0x00030000).be32(0).be16(0).be16(0).be32(1).pad(16).done()
        val l = ArrayList<Pair<String, ByteArray>>()
        l.add("OS/2" to os2)
        l.add((if (cff) "CFF " else "glyf") to ByteArray(8))
        l.add("head" to headTable()); l.add("hhea" to hhea); l.add("maxp" to maxp)
        l.add("name" to nameTable()); l.add("post" to post)
        if (fvar) {
            val f = W().be16(1).be16(0).be16(16).be16(2).be16(1).be16(20).be16(2).be16(8)
                .ascii("wght").be32(100L shl 16).be32(400L shl 16).be32(900L shl 16).be16(256).be16(0).done()
            l.add("fvar" to f)
        }
        return l
    }

    private fun sfnt(ver: Long, tabs: List<Pair<String, ByteArray>>, base: Int = 0): ByteArray {
        val w = W()
        w.be32(ver).be16(tabs.size).be16(0).be16(0).be16(0)
        var off = base + 12 + 16 * tabs.size
        for ((t, d) in tabs) { w.ascii(t).be32(0).be32(off.toLong()).be32(d.size.toLong()); off += (d.size + 3) / 4 * 4 }
        for ((_, d) in tabs) { w.bytes(d); w.pad((4 - d.size % 4) % 4) }
        return w.done()
    }

    private fun ttf() = sfnt(0x00010000, tables(false, true))
    private fun otf() = sfnt(0x4F54544F, tables(true))

    private fun ttc(): ByteArray {
        val a = sfnt(0x00010000, tables(false), 20)
        val b = sfnt(0x00010000, tables(false).map { if (it.first == "name") it.first to nameTable() else it }, 20 + a.size)
        return W().ascii("ttcf").be32(0x00010000).be32(2).be32(20).be32(20L + a.size).bytes(a).bytes(b).done()
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    private fun woff(): ByteArray {
        val tabs = tables(false).sortedBy { it.first }
        val meta = deflate("<metadata version=\"1.0\"><vendor name=\"Test Foundry\" url=\"http://example.com\"/></metadata>".toByteArray())
        val metaOrig = "<metadata version=\"1.0\"><vendor name=\"Test Foundry\" url=\"http://example.com\"/></metadata>".length
        val datas = tabs.map { (_, d) -> val c = deflate(d); if (c.size < d.size) c else d }
        var off = 44 + 20 * tabs.size
        val dir = W()
        for ((i, t) in tabs.withIndex()) {
            dir.ascii(t.first).be32(off.toLong()).be32(datas[i].size.toLong()).be32(t.second.size.toLong()).be32(0)
            off += (datas[i].size + 3) / 4 * 4
        }
        val body = W()
        for (d in datas) { body.bytes(d); body.pad((4 - d.size % 4) % 4) }
        val metaOff = off
        val total = off + meta.size
        val sfntSize = 12 + 16 * tabs.size + tabs.sumOf { (it.second.size + 3) / 4 * 4 }
        val w = W()
        w.ascii("wOFF").be32(0x00010000).be32(total.toLong()).be16(tabs.size).be16(0).be32(sfntSize.toLong())
            .be16(1).be16(5).be32(metaOff.toLong()).be32(meta.size.toLong()).be32(metaOrig.toLong()).be32(0).be32(0)
        return w.bytes(dir.done()).bytes(body.done()).bytes(meta).done()
    }

    private fun woff2(): ByteArray {
        val dir = W()
        dir.u8(1).u8(54)                      // head, orig 54
        dir.u8(10).u8(0x87).u8(0x68).u8(0x87).u8(0x04) // glyf transformed v0: orig 1000, transformLength 900
        dir.u8(5).u8(0x82).u8(0x2C)           // name, orig 300
        dir.u8(63).ascii("ZZZZ").u8(5)         // arbitrary tag
        val data = ByteArray(16)
        val total = 48 + dir.size() + data.size
        return W().ascii("wOF2").be32(0x00010000).be32(total.toLong()).be16(4).be16(0).be32(5000).be32(16)
            .be16(1).be16(0).be32(0).be32(0).be32(0).be32(0).be32(0).bytes(dir.done()).bytes(data).done()
    }

    private fun bdf() = ("STARTFONT 2.1\nCOMMENT test font\nFONT -Test-Fixed-Medium-R-Normal--13-120-75-75-C-70-ISO8859-1\n" +
        "SIZE 12 75 75\nFONTBOUNDINGBOX 7 13 0 -2\nSTARTPROPERTIES 3\nFAMILY_NAME \"Fixed\"\nPIXEL_SIZE 13\nSPACING \"C\"\nENDPROPERTIES\n" +
        "CHARS 2\nSTARTCHAR A\nENCODING 65\nSWIDTH 600 0\nDWIDTH 7 0\nBBX 7 13 0 -2\nBITMAP\n00\nENDCHAR\n" +
        "STARTCHAR B\nENCODING 66\nSWIDTH 600 0\nDWIDTH 7 0\nBBX 7 13 0 -2\nBITMAP\n00\nENDCHAR\nENDFONT\n").toByteArray()

    private fun pcf(): ByteArray {
        val strings = W().ascii("FAMILY_NAME").u8(0).ascii("PIXEL_SIZE").u8(0).ascii("Fixed").u8(0).done()
        val prop = W().le32(0).le32(2)
            .le32(0).u8(1).le32(23)
            .le32(12).u8(0).le32(13)
        prop.padTo(28)
        prop.le32(strings.size.toLong()).bytes(strings)
        val p = prop.done()
        val bm = W().le32(0).le32(3).pad(12).done()
        val tocEnd = 8 + 32
        return W().u8(1).ascii("fcp").le32(2)
            .le32(1).le32(0).le32(p.size.toLong()).le32(tocEnd.toLong())
            .le32(8).le32(0).le32(bm.size.toLong()).le32((tocEnd + p.size).toLong())
            .bytes(p).bytes(bm).done()
    }

    private fun nes(): ByteArray = W().ascii("NES").u8(0x1A).u8(2).u8(1).u8(0x13).u8(0x40).pad(8).pad(2 * 16384 + 8192).done()

    private fun nes2(): ByteArray = W().ascii("NES").u8(0x1A).u8(8).u8(0).u8(0x10).u8(0x08).u8(0x21).u8(0x00).u8(0x07).u8(0x70).u8(1).pad(3)
        .pad(0x108 * 16384).done()

    private val GB_LOGO = intArrayOf(
        0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
        0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
        0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E)

    private fun gb(cgb: Int = 0, goodSum: Boolean = true): ByteArray {
        val b = ByteArray(32768)
        for (i in GB_LOGO.indices) b[0x104 + i] = GB_LOGO[i].toByte()
        val t = "TESTGAME".toByteArray()
        for (i in t.indices) b[0x134 + i] = t[i]
        b[0x143] = cgb.toByte(); b[0x146] = 3; b[0x147] = 3; b[0x148] = 0; b[0x149] = 2; b[0x14A] = 1; b[0x14B] = 0x33; b[0x14C] = 2
        b[0x144] = '0'.code.toByte(); b[0x145] = '1'.code.toByte()
        var x = 0
        for (i in 0x134..0x14C) x = (x - (b[i].toInt() and 0xFF) - 1) and 0xFF
        b[0x14D] = (if (goodSum) x else (x + 1) and 0xFF).toByte()
        return b
    }

    private fun gba(): ByteArray {
        val b = ByteArray(0x200)
        val logo = intArrayOf(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21, 0x3D, 0x84, 0x82, 0x0A)
        for (i in logo.indices) b[4 + i] = logo[i].toByte()
        val t = "TESTGBA".toByteArray(); for (i in t.indices) b[0xA0 + i] = t[i]
        val gc = "AXYE".toByteArray(); for (i in gc.indices) b[0xAC + i] = gc[i]
        b[0xB0] = '0'.code.toByte(); b[0xB1] = '1'.code.toByte(); b[0xB2] = 0x96.toByte(); b[0xBC] = 1
        var c = 0
        for (i in 0xA0..0xBC) c -= (b[i].toInt() and 0xFF)
        b[0xBD] = ((c - 0x19) and 0xFF).toByte()
        return b
    }

    private fun snes(map: Int, headerAt: Int, size: Int, copier: Boolean = false): ByteArray {
        val b = ByteArray(size)
        val t = "TEST SNES ROM".padEnd(21).toByteArray()
        for (i in t.indices) b[headerAt + i] = t[i]
        b[headerAt + 0x15] = map.toByte(); b[headerAt + 0x16] = 0; b[headerAt + 0x17] = 8; b[headerAt + 0x18] = 3
        b[headerAt + 0x19] = 1; b[headerAt + 0x1A] = 0x33; b[headerAt + 0x1B] = 0
        b[headerAt + 0x1C] = 0xCB.toByte(); b[headerAt + 0x1D] = 0xED.toByte(); b[headerAt + 0x1E] = 0x34; b[headerAt + 0x1F] = 0x12
        b[headerAt + 0x3C] = 0; b[headerAt + 0x3D] = 0x80.toByte()
        return if (copier) ByteArray(512) + b else b
    }

    private fun z64(): ByteArray {
        val w = W().be32(0x80371240).be32(0x0F).be32(0x80000400).be32(0x1444).be32(0xA0B1C2D3).be32(0x01020304).pad(8)
            .fixedAscii("TEST N64 ROM", 20, 0x20).pad(7).ascii("N").ascii("TE").ascii("E").u8(1).pad(0x1000 - 0x40)
        return w.done()
    }
    private fun v64(): ByteArray { val z = z64(); return ByteArray(z.size) { z[it xor 1] } }
    private fun n64(): ByteArray { val z = z64(); return ByteArray(z.size) { z[it xor 3] } }

    private fun genesisImage(): ByteArray {
        val w = W().pad(0x100)
        w.fixedAscii("SEGA MEGA DRIVE ", 16).fixedAscii("(C)TEST 2024.JAN", 16)
        w.fixedAscii("TEST DOMESTIC TITLE", 48, 0x20).fixedAscii("TEST OVERSEAS TITLE", 48, 0x20)
        w.fixedAscii("GM 00000000-00", 14, 0x20).be16(0xABCD).fixedAscii("J", 16, 0x20)
        w.be32(0).be32(0x1FFFF).be32(0xFF0000).be32(0xFFFFFF)
        w.padTo(0x1F0); w.ascii("JUE").pad(13)
        w.padTo(0x4000)
        return w.done()
    }
    private fun smd(): ByteArray {
        val d = genesisImage()
        val blk = ByteArray(16384)
        for (i in 0 until 8192) { blk[i] = d[2 * i + 1]; blk[8192 + i] = d[2 * i] }
        val h = ByteArray(512); h[8] = 0xAA.toByte(); h[9] = 0xBB.toByte(); h[1] = 3
        return h + blk
    }

    private fun crc16(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0xFFFF
        for (i in from until to) { crc = crc xor (b[i].toInt() and 0xFF); repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 } }
        return crc
    }

    private fun nds(goodCrc: Boolean = true): ByteArray {
        val b = ByteArray(0x200)
        val t = "TESTDS".toByteArray(); for (i in t.indices) b[i] = t[i]
        val gc = "ABCD".toByteArray(); for (i in gc.indices) b[0xC + i] = gc[i]
        b[0x10] = '0'.code.toByte(); b[0x11] = '1'.code.toByte(); b[0x12] = 0; b[0x14] = 3
        val logo = intArrayOf(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21, 0x3D, 0x84, 0x82, 0x0A)
        for (i in logo.indices) b[0xC0 + i] = logo[i].toByte()
        b[0x80] = 0x00; b[0x81] = 0x02
        val lc = crc16(b, 0xC0, 0x15C); b[0x15C] = lc.toByte(); b[0x15D] = (lc shr 8).toByte()
        val hc = crc16(b, 0, 0x15E) xor (if (goodCrc) 0 else 1)
        b[0x15E] = hc.toByte(); b[0x15F] = (hc shr 8).toByte()
        return b
    }

    private fun nbtBytes(): ByteArray {
        val w = W()
        fun name(s: String) { val b = s.toByteArray(); w.be16(b.size).bytes(b) }
        w.u8(10); name("Level")
        w.u8(8); name("LevelName"); name("My World")
        w.u8(3); name("version"); w.be32(19133)
        w.u8(1); name("hardcore"); w.u8(0)
        w.u8(4); name("Time"); w.be64(123456789L)
        w.u8(9); name("Pos"); w.u8(6); w.be32(3); repeat(3) { w.be64(0x3FF0000000000000L) }
        w.u8(7); name("Raw"); w.be32(5); w.pad(5)
        w.u8(10); name("Sub"); w.u8(3); name("a"); w.be32(1); w.u8(10); name("deep"); w.u8(0); w.u8(0)
        w.u8(0)
        return w.done()
    }
    private fun gz(b: ByteArray): ByteArray { val o = ByteArrayOutputStream(); GZIPOutputStream(o).use { it.write(b) }; return o.toByteArray() }
    private fun zl(b: ByteArray): ByteArray = deflate(b)

    private fun mca(): ByteArray {
        val chunkNbt = W().apply {
            u8(10); be16(0)
            u8(3); be16(11); ascii("DataVersion"); be32(3465)
            u8(8); be16(6); ascii("Status"); be16(14); ascii("minecraft:full")
            u8(0)
        }.done()
        val z = zl(chunkNbt)
        val header = ByteArray(8192)
        fun put(i: Int, e: Long) { header[i * 4] = (e shr 24).toByte(); header[i * 4 + 1] = (e shr 16).toByte(); header[i * 4 + 2] = (e shr 8).toByte(); header[i * 4 + 3] = e.toByte() }
        put(0, (2L shl 8) or 1); put(5, (2L shl 8) or 1)
        val ts = 1700000000L
        for (i in intArrayOf(0, 5)) { val o = 4096 + i * 4; header[o] = (ts shr 24).toByte(); header[o + 1] = (ts shr 16).toByte(); header[o + 2] = (ts shr 8).toByte(); header[o + 3] = ts.toByte() }
        val chunk = W().be32(z.size + 1L).u8(2).bytes(z).padTo(4096).done()
        return header + chunk
    }

    private fun pak(): ByteArray {
        val files = listOf("maps/start.bsp" to ByteArray(10), "progs/player.mdl" to ByteArray(20), "sound/a.wav" to ByteArray(5))
        val data = W().ascii("PACK").le32(0).le32(0)
        val pos = ArrayList<Int>()
        for ((_, d) in files) { pos.add(data.size()); data.bytes(d) }
        val dirOff = data.size()
        for ((i, f) in files.withIndex()) data.fixedAscii(f.first, 56).le32(pos[i].toLong()).le32(f.second.size.toLong())
        val b = data.done()
        fun set(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
        set(4, dirOff); set(8, files.size * 64)
        return b
    }

    private fun wad(magic: String = "PWAD"): ByteArray {
        val w = W().ascii(magic).le32(3).le32(0)
        val lumpData = ByteArray(8)
        val pos = w.size(); w.bytes(lumpData)
        val dirOff = w.size()
        for (n in listOf("E1M1", "THINGS", "MAP02")) w.le32(pos.toLong()).le32(8).fixedAscii(n, 8)
        val b = w.done()
        b[8] = dirOff.toByte(); b[9] = (dirOff shr 8).toByte()
        return b
    }

    private fun unity() = W().ascii("UnityFS").u8(0).be32(6).ascii("5.x.x").u8(0).ascii("2019.4.10f1").u8(0)
        .be64(120).be32(40).be32(80).be32(0x42).pad(60).done()

    private fun pgn() = ("[Event \"Test Open\"]\n[Site \"Testville\"]\n[Date \"2024.01.02\"]\n[Round \"1\"]\n[White \"Alice\"]\n[Black \"Bob\"]\n" +
        "[Result \"1-0\"]\n[WhiteElo \"2100\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0\n\n" +
        "[Event \"Test Open\"]\n[White \"Carol\"]\n[Black \"Dave\"]\n[Result \"1/2-1/2\"]\n\n1. d4 d5 1/2-1/2\n").toByteArray()

    private fun fen() = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1\n".toByteArray()

    // ---------------------------------------------------------------- helpers

    private fun checkRobust(name: String, i: FormatInspector, b: ByteArray, fileName: String? = null) {
        // truncation: every prefix
        val sizes = LinkedHashSet<Int>()
        for (n in 0..minOf(b.size, 1500)) sizes.add(n)
        var n = 1500; while (n < b.size) { sizes.add(n); n += 97 }
        sizes.add(b.size); sizes.add(b.size - 1)
        for (s in sizes) if (s in 0..b.size) {
            try { i.inspect(ByteArraySource(b.copyOf(s)), fileName) } catch (t: Throwable) { fail("$name: prefix $s threw $t") }
        }
        // hostile length fields: every 4-byte window overwritten with 0xFFFFFFFF
        val pos = LinkedHashSet<Int>()
        for (p in 0 until minOf(b.size, 1500)) pos.add(p)
        for (p in maxOf(0, b.size - 400) until b.size) pos.add(p)
        var q = 1500; while (q < b.size) { pos.add(q); q += 211 }
        for (p in listOf(0x100, 0x7FC0, 0xFFC0, 0x15C, 0x4000)) if (p < b.size) { for (d in -8..8) if (p + d in 0 until b.size) pos.add(p + d) }
        val t0 = System.nanoTime()
        for (p in pos) {
            val m = b.copyOf()
            for (k in 0 until 4) if (p + k < m.size) m[p + k] = 0xFF.toByte()
            try { i.inspect(ByteArraySource(m), fileName) } catch (t: Throwable) { fail("$name: hostile window at $p threw $t") }
        }
        assertTrue("$name: hostile fuzz too slow", (System.nanoTime() - t0) < 60_000_000_000L)
    }

    private fun hugeCheck(name: String, i: FormatInspector, b: ByteArray, positions: List<Int>) {
        for (p in listOf(-1) + positions) {
            val m = b.copyOf()
            if (p >= 0) for (k in 0 until 4) if (p + k < m.size) m[p + k] = 0xFF.toByte()
            val t0 = System.nanoTime()
            try { i.inspect(HugeSource(m), "x") } catch (t: Throwable) { fail("$name: huge source at $p threw $t") }
            assertTrue("$name: huge source at $p too slow", (System.nanoTime() - t0) < 20_000_000_000L)
        }
    }

    // ---------------------------------------------------------------- fonts

    @Test fun ttfFacts() {
        val r = insp(FontSfntInspector, ttf(), "x.ttf")!!
        assertEquals("ttf", r.formatId)
        assertEquals("Test Sans Bold", r.title)
        assertEquals("Test Sans", r.f("Family")); assertEquals("Bold", r.f("Subfamily"))
        assertEquals("Test Sans Bold", r.f("Full name")); assertEquals("Version 1.500", r.f("Version"))
        assertEquals("TestSans-Bold", r.f("PostScript name")); assertEquals("Ann Designer", r.f("Designer"))
        assertEquals("Copyright 2024 Test", r.f("Copyright")); assertEquals("Free license", r.f("License"))
        assertEquals("2048", r.f("Units per em")); assertEquals("512", r.f("Glyph count"))
        assertEquals("1.5", r.f("Font revision"))
        assertEquals("2024-01-01T00:00:00Z", r.f("Created")); assertEquals("2024-01-02T00:00:00Z", r.f("Modified"))
        assertEquals("-100, -200 to 1100, 900", r.f("Bounding box"))
        assertEquals("bold", r.f("Mac style"))
        assertEquals("1900 / -500 / 0", r.f("Ascender / descender / line gap"))
        assertEquals("700 (Bold)", r.f("Weight class")); assertEquals("5 (Normal)", r.f("Width class"))
        assertEquals("installable", r.f("Embedding")); assertEquals("TEST", r.f("Vendor ID"))
        assertEquals("bold", r.f("Selection flags"))
        assertEquals("yes", r.f("Monospaced"))
        assertEquals("TrueType (glyf)", r.f("Outlines"))
        assertEquals("variable", r.f("Features"))
        assertEquals("wght 100-900 (default 400)", r.f("Axes")); assertEquals("1", r.f("Variation axes")); assertEquals("2", r.f("Named instances"))
        assertTrue(r.f("Tables")!!.startsWith("8: "))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun otfIsCff() {
        val r = insp(FontSfntInspector, otf(), "x.otf")!!
        assertEquals("otf", r.formatId); assertEquals("PostScript (CFF)", r.f("Outlines"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun otfWithoutCffWarns() {
        val r = insp(FontSfntInspector, sfnt(0x4F54544F, tables(false)))!!
        assertTrue(r.warnings.any { "CFF" in it })
    }

    @Test fun ttcMembers() {
        val r = insp(FontSfntInspector, ttc(), "x.ttc")!!
        assertEquals("ttc", r.formatId); assertEquals("2", r.f("Fonts in collection"))
        assertEquals("Test Sans Bold, Test Sans Bold", r.f("Members"))
        assertEquals("Test Sans", r.f("Family"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun sfntRejectsGarbage() {
        assertNull(insp(FontSfntInspector, ByteArray(100)))
        assertNull(insp(FontSfntInspector, "hello world, not a font".toByteArray()))
        assertNull(insp(FontSfntInspector, ByteArray(0)))
    }

    @Test fun sfntTableBeyondFileWarns() {
        val b = ttf().copyOf(300)
        val r = insp(FontSfntInspector, b)!!
        assertTrue(r.warnings.any { "beyond" in it })
    }

    @Test fun woffFacts() {
        val r = insp(FontWoffInspector, woff(), "x.woff")!!
        assertEquals("woff", r.formatId); assertEquals("TrueType", r.f("Flavor")); assertEquals("7", r.f("Table count"))
        assertEquals("1.5", r.f("Font version"))
        assertEquals("Test Sans", r.f("Family")); assertEquals("512", r.f("Glyph count")); assertEquals("2048", r.f("Units per em"))
        assertEquals("Test Foundry", r.f("Metadata vendor"))
        assertTrue(r.text!!.contains("Test Foundry"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun woff2Header() {
        val r = insp(FontWoff2Inspector, woff2(), "x.woff2")!!
        assertEquals("woff2", r.formatId); assertEquals("TrueType", r.f("Flavor")); assertEquals("4", r.f("Table count"))
        assertEquals("4: head, glyf, name, ZZZZ", r.f("Tables"))
        assertEquals("1.3 KiB", r.f("Sum of table sizes"))
        assertEquals("applied", r.f("glyf transform"))
        assertEquals("TrueType (glyf)", r.f("Outlines"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun bdfFacts() {
        val r = insp(FontBdfInspector, bdf(), "x.bdf")!!
        assertEquals("Fixed", r.title)
        assertEquals("2.1", r.f("BDF version"))
        assertEquals("-Test-Fixed-Medium-R-Normal--13-120-75-75-C-70-ISO8859-1", r.f("Font name"))
        assertEquals("12 pt at 75x75 dpi", r.f("Size"))
        assertEquals("7 x 13 pixels, offset 0, -2", r.f("Bounding box"))
        assertEquals("2", r.f("Glyph count (declared)")); assertEquals("2", r.f("Glyph count (found)"))
        assertEquals("Fixed", r.f("FAMILY_NAME")); assertEquals("13", r.f("PIXEL_SIZE"))
        assertEquals("test font", r.text)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun bdfRejectsBiosemi() {
        assertNull(insp(FontBdfInspector, W().u8(0xFF).ascii("BIOSEMI").pad(300).done()))
    }

    @Test fun pcfFacts() {
        val r = insp(FontPcfInspector, pcf(), "x.pcf")!!
        assertEquals("Fixed", r.title); assertEquals("3", r.f("Glyph count")); assertEquals("2", r.f("Tables"))
        assertEquals("Fixed", r.f("FAMILY_NAME")); assertEquals("13", r.f("PIXEL_SIZE"))
        assertTrue(r.warnings.isEmpty())
    }

    // ---------------------------------------------------------------- ROMs

    @Test fun nesIntel() {
        val r = insp(GameNesInspector, nes())!!
        assertEquals("iNES", r.f("Header")); assertEquals("65", r.f("Mapper"))
        assertEquals("32.0 KiB", r.f("PRG ROM")); assertEquals("8.0 KiB", r.f("CHR ROM"))
        assertEquals("vertical", r.f("Mirroring")); assertEquals("yes", r.f("Battery-backed")); assertEquals("no", r.f("Trainer"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun nesMapperValue() {
        val r = insp(GameNesInspector, nes())!!
        assertEquals("65", r.f("Mapper"))   // (0x13 >> 4) | (0x40 & 0xF0) = 0x41 = 65
    }

    @Test fun nes2Facts() {
        val r = insp(GameNesInspector, nes2())!!
        assertEquals("NES 2.0", r.f("Header"))
        assertEquals("257", r.f("Mapper")); assertEquals("2", r.f("Submapper"))
        assertEquals("128.0 KiB", r.f("PRG ROM")); assertEquals("none (uses CHR RAM)", r.f("CHR ROM"))
        assertEquals("8.0 KiB", r.f("PRG RAM")); assertEquals("8.0 KiB", r.f("CHR NVRAM")); assertEquals("PAL (RP2C07)", r.f("CPU/PPU timing"))
    }

    @Test fun nesTruncatedWarns() {
        val b = nes().copyOf(5000)
        assertTrue(insp(GameNesInspector, b)!!.warnings.any { "smaller" in it })
    }

    @Test fun gameBoy() {
        val r = insp(GameGameBoyInspector, gb(), "x.gb")!!
        assertEquals("gb", r.formatId); assertEquals("TESTGAME", r.title)
        assertEquals("original Game Boy (no CGB flag)", r.f("Color support"))
        assertEquals("MBC1+RAM+BATTERY (0x03)", r.f("Cartridge type"))
        assertEquals("32.0 KiB, 2 banks of 16 KiB", r.f("ROM size (header)"))
        assertEquals("8 KiB", r.f("RAM size")); assertEquals("outside Japan", r.f("Destination"))
        assertEquals("yes", r.f("Super Game Boy support")); assertEquals("01", r.f("New licensee code"))
        assertTrue(r.f("Header checksum")!!.endsWith("(valid)"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun gameBoyColorAndBadChecksum() {
        val r = insp(GameGameBoyInspector, gb(0x80, false))!!
        assertEquals("gbc", r.formatId); assertEquals("works on Game Boy and Game Boy Color", r.f("Color support"))
        assertTrue(r.f("Header checksum")!!.contains("INVALID"))
        assertTrue(r.warnings.any { "checksum" in it })
    }

    @Test fun gameBoyRejectsNoLogo() { assertNull(insp(GameGameBoyInspector, ByteArray(0x200))) }

    @Test fun gbaFacts() {
        val r = insp(GameGbaInspector, gba())!!
        assertEquals("TESTGBA", r.title); assertEquals("AXYE", r.f("Game code")); assertEquals("01 (Nintendo)", r.f("Maker code"))
        assertEquals("1", r.f("Software version"))
        assertTrue(r.f("Header checksum")!!.endsWith("(valid)"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun snesLoRom() {
        val r = insp(GameSnesInspector, snes(0x20, 0x7FC0, 0x8000))!!
        assertEquals("TEST SNES ROM", r.title); assertTrue(r.f("Mapping")!!.startsWith("LoROM"))
        assertEquals("SlowROM", r.f("Speed")); assertEquals("256.0 KiB", r.f("ROM size (header)")); assertEquals("8.0 KiB", r.f("SRAM size"))
        assertTrue(r.f("Checksum / complement")!!.contains("consistent"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun snesHiRomFast() {
        val r = insp(GameSnesInspector, snes(0x31, 0xFFC0, 0x10000))!!
        assertTrue(r.f("Mapping")!!.startsWith("HiROM")); assertEquals("FastROM", r.f("Speed"))
    }

    @Test fun snesCopierHeader() {
        val r = insp(GameSnesInspector, snes(0x20, 0x7FC0, 0x8000, true))!!
        assertEquals("512 bytes", r.f("Copier header")); assertEquals("TEST SNES ROM", r.title)
    }

    @Test fun snesNoHeaderWarns() {
        val r = insp(GameSnesInspector, ByteArray(0x8000), "x.sfc")!!
        assertTrue(r.warnings.any { "no valid" in it })
    }

    @Test fun n64AllOrders() {
        for ((b, id) in listOf(z64() to "z64", v64() to "v64", n64() to "n64")) {
            val r = insp(GameN64Inspector, b)!!
            assertEquals(id, r.formatId)
            assertEquals("TEST N64 ROM", r.title)
            assertEquals("NTEE", r.f("Game code")); assertEquals("North America", r.f("Region"))
            assertEquals("game pak", r.f("Media format")); assertEquals("0x80000400", r.f("Boot address"))
            assertEquals("0xA0B1C2D3 / 0x01020304 (not verified)", r.f("CRC1 / CRC2"))
        }
        assertNull(insp(GameN64Inspector, ByteArray(0x100)))
    }

    @Test fun genesis() {
        val r = insp(GameGenesisInspector, genesisImage())!!
        assertEquals("md", r.formatId); assertEquals("SEGA MEGA DRIVE", r.f("System"))
        assertEquals("TEST OVERSEAS TITLE", r.title); assertEquals("TEST DOMESTIC TITLE", r.f("Domestic title"))
        assertEquals("(C)TEST 2024.JAN", r.f("Copyright / release")); assertEquals("GM 00000000-00", r.f("Serial / type"))
        assertEquals("0xABCD (not verified)", r.f("Checksum (stored)")); assertEquals("JUE", r.f("Region codes"))
        assertEquals("0x00000000 - 0x0001FFFF", r.f("ROM address range"))
    }

    @Test fun genesisSmd() {
        val r = insp(GameGenesisInspector, smd())!!
        assertEquals("smd", r.formatId); assertEquals("TEST OVERSEAS TITLE", r.title)
        assertTrue(r.f("Layout")!!.contains("SMD"))
    }

    @Test fun ndsFacts() {
        val r = insp(GameNdsInspector, nds())!!
        assertEquals("nds", r.formatId); assertEquals("TESTDS", r.title); assertEquals("ABCD", r.f("Game code"))
        assertEquals("01 (Nintendo)", r.f("Maker code")); assertEquals("Nintendo DS", r.f("Unit code"))
        assertEquals("1.0 MiB", r.f("Device capacity"))
        assertTrue(r.f("Header checksum")!!.endsWith("(valid)")); assertTrue(r.f("Logo checksum")!!.endsWith("(valid)"))
        assertTrue(r.warnings.isEmpty())
        assertTrue(insp(GameNdsInspector, nds(false))!!.warnings.any { "CRC" in it })
    }

    @Test fun crcHelperMatchesModbusCheckValue() {
        assertEquals(0x4B37, crc16("123456789".toByteArray(), 0, 9))
        assertEquals(0x4B37, Fg.crc16Modbus("123456789".toByteArray(), 0, 9))
    }

    // ---------------------------------------------------------------- Minecraft

    private fun checkNbt(r: InspectionResult, comp: String) {
        assertEquals(comp, r.f("Compression"))
        assertEquals("compound named \"Level\"", r.f("Root tag"))
        assertEquals("My World", r.title)
        assertEquals("string: My World", r.f("LevelName")); assertEquals("int: 19133", r.f("version"))
        assertEquals("byte: 0", r.f("hardcore")); assertEquals("long: 123456789", r.f("Time"))
        assertEquals("list: 3 double element(s)", r.f("Pos")); assertEquals("byte[]: 5 bytes", r.f("Raw"))
        assertEquals("compound: 2 entries", r.f("Sub"))
        assertEquals("7", r.f("Top-level entries"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun nbtGzip() { checkNbt(insp(GameNbtInspector, gz(nbtBytes()), "level.dat")!!, "gzip") }
    @Test fun nbtZlib() { checkNbt(insp(GameNbtInspector, zl(nbtBytes()))!!, "zlib") }
    @Test fun nbtRaw() { checkNbt(insp(GameNbtInspector, nbtBytes())!!, "raw (uncompressed)") }
    @Test fun nbtRejects() { assertNull(insp(GameNbtInspector, "plain text file".toByteArray())) }

    @Test fun nbtHostileListCountDoesNotHang() {
        val w = W().u8(10).be16(0).u8(9).be16(1).ascii("L").u8(10).be32(0x7FFFFFFF).u8(0)
        val r = insp(GameNbtInspector, w.done())
        assertNotNull(r)
    }

    @Test fun mcaFacts() {
        val r = insp(GameMcaInspector, mca(), "r.-1.2.mca")!!
        assertEquals("x=-1, z=2", r.f("Region coordinates")); assertEquals("2 of 1024", r.f("Chunks present"))
        assertEquals("2023-11-14T22:13:20Z", r.f("Newest chunk timestamp"))
        assertEquals("zlib", r.f("First chunk compression")); assertEquals("3465", r.f("First chunk DataVersion"))
        assertEquals("minecraft:full", r.f("First chunk status"))
        assertTrue(r.warnings.isEmpty())
    }

    // ---------------------------------------------------------------- engine archives

    @Test fun pakFacts() {
        val r = insp(GamePakInspector, pak())!!
        assertEquals("3", r.f("Entries")); assertEquals("35 B", r.f("Total stored size"))
        assertEquals("maps/start.bsp, progs/player.mdl, sound/a.wav", r.f("First entries"))
        assertEquals("bsp x1, mdl x1, wav x1", r.f("File types"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun wadFacts() {
        val r = insp(GameWadInspector, wad())!!
        assertEquals("PWAD (patch / mod data)", r.f("Type")); assertEquals("3", r.f("Lumps"))
        assertEquals("E1M1, THINGS, MAP02", r.f("Lump names")); assertEquals("2: E1M1, MAP02", r.f("Maps"))
        assertTrue(r.warnings.isEmpty())
        assertEquals("IWAD (base game data)", insp(GameWadInspector, wad("IWAD"))!!.f("Type"))
    }

    @Test fun unityFacts() {
        val r = insp(GameUnityInspector, unity())!!
        assertEquals("UnityFS", r.f("Signature")); assertEquals("6", r.f("Bundle format version"))
        assertEquals("5.x.x", r.f("Minimum player version")); assertEquals("2019.4.10f1", r.f("Engine version"))
        assertEquals("LZ4", r.f("Block info compression")); assertEquals("combined with block info", r.f("Directory info"))
        assertEquals("40 compressed, 80 uncompressed", r.f("Block info size"))
        assertTrue(r.warnings.any { "differs" in it } || r.warnings.isEmpty())
    }

    // ---------------------------------------------------------------- chess

    @Test fun pgnFacts() {
        val r = insp(GamePgnInspector, pgn(), "x.pgn")!!
        assertEquals("2", r.f("Games")); assertEquals("Test Open", r.f("Event")); assertEquals("Alice", r.f("White"))
        assertEquals("Bob", r.f("Black")); assertEquals("1-0", r.f("Result")); assertEquals("2100", r.f("WhiteElo"))
        assertEquals("1-0 x1, 1/2-1/2 x1", r.f("Results"))
        assertEquals("1. Alice vs Bob (1-0); 2. Carol vs Dave (1/2-1/2)", r.f("Games listed"))
        assertTrue(r.text!!.contains("1. e4 e5"))
        assertTrue(r.warnings.isEmpty())
        assertNull(insp(GamePgnInspector, "just some text\nnothing here".toByteArray()))
    }

    @Test fun fenFacts() {
        val r = insp(GameFenInspector, fen())!!
        assertEquals("1", r.f("Positions")); assertEquals("Black", r.f("Side to move")); assertEquals("KQkq", r.f("Castling rights"))
        assertEquals("e3", r.f("En passant target")); assertEquals("0", r.f("Halfmove clock")); assertEquals("1", r.f("Fullmove number"))
        assertEquals("white 16, black 16", r.f("Pieces")); assertTrue(r.warnings.isEmpty())
        assertNull(insp(GameFenInspector, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1".toByteArray()))
        assertNull(insp(GameFenInspector, "hello".toByteArray()))
        val bad = insp(GameFenInspector, "8/8/8/8/8/8/8/8 w - - 0 1".toByteArray())!!
        assertTrue(bad.warnings.any { "king" in it })
    }

    // ---------------------------------------------------------------- registry

    @Test fun registryCoversEveryDeclaredId() {
        val ids = FontGameInspectors.all.flatMap { it.formatIds }
        for (id in listOf("ttf", "otf", "ttc", "woff", "woff2", "bdf", "pcf", "nes", "gb", "gbc", "gba", "sfc", "z64", "n64", "v64",
            "md", "smd", "nds", "nbt", "mca", "pak", "wad", "unity3d", "pgn", "fen")) assertTrue(id, id in ids)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(FontGameInspectors.all.size, FontGameInspectors.all.toSet().size)
    }

    @Test fun emptyInputReturnsNullEverywhere() {
        for (i in FontGameInspectors.all) assertNull(i.toString(), insp(i, ByteArray(0)))
    }

    // ---------------------------------------------------------------- robustness: truncation + hostile lengths

    @Test(timeout = 120000) fun robustFonts() {
        checkRobust("ttf", FontSfntInspector, ttf()); checkRobust("otf", FontSfntInspector, otf()); checkRobust("ttc", FontSfntInspector, ttc())
        checkRobust("woff", FontWoffInspector, woff()); checkRobust("woff2", FontWoff2Inspector, woff2())
        checkRobust("bdf", FontBdfInspector, bdf()); checkRobust("pcf", FontPcfInspector, pcf())
    }

    @Test(timeout = 240000) fun robustRoms() {
        checkRobust("nes", GameNesInspector, nes().copyOf(4096)); checkRobust("nes2", GameNesInspector, nes2().copyOf(4096))
        checkRobust("gb", GameGameBoyInspector, gb()); checkRobust("gba", GameGbaInspector, gba())
        checkRobust("snes", GameSnesInspector, snes(0x20, 0x7FC0, 0x8000)); checkRobust("snesHi", GameSnesInspector, snes(0x21, 0xFFC0, 0x10000))
        checkRobust("n64", GameN64Inspector, z64()); checkRobust("n64v", GameN64Inspector, v64()); checkRobust("n64n", GameN64Inspector, n64())
        checkRobust("md", GameGenesisInspector, genesisImage()); checkRobust("smd", GameGenesisInspector, smd())
        checkRobust("nds", GameNdsInspector, nds())
    }

    @Test(timeout = 240000) fun robustGames() {
        checkRobust("nbtgz", GameNbtInspector, gz(nbtBytes())); checkRobust("nbtraw", GameNbtInspector, nbtBytes())
        checkRobust("mca", GameMcaInspector, mca(), "r.0.0.mca")
        checkRobust("pak", GamePakInspector, pak()); checkRobust("wad", GameWadInspector, wad()); checkRobust("unity", GameUnityInspector, unity())
        checkRobust("pgn", GamePgnInspector, pgn()); checkRobust("fen", GameFenInspector, fen())
    }

    @Test(timeout = 240000) fun hugeFileSizeWithHostileFields() {
        val hdr = listOf(0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 0x100, 0x118, 0x15C)
        hugeCheck("ttf", FontSfntInspector, ttf(), hdr + listOf(12, 20, 24, 28))
        hugeCheck("woff", FontWoffInspector, woff(), hdr)
        hugeCheck("woff2", FontWoff2Inspector, woff2(), hdr)
        hugeCheck("pcf", FontPcfInspector, pcf(), hdr)
        hugeCheck("bdf", FontBdfInspector, bdf(), hdr)
        hugeCheck("nes", GameNesInspector, nes().copyOf(64), hdr)
        hugeCheck("gb", GameGameBoyInspector, gb(), hdr)
        hugeCheck("snes", GameSnesInspector, snes(0x20, 0x7FC0, 0x8000), listOf(0x7FC0, 0x7FDC))
        hugeCheck("md", GameGenesisInspector, genesisImage(), hdr + listOf(0x1A0, 0x1A4))
        hugeCheck("nds", GameNdsInspector, nds(), hdr + listOf(0x80))
        hugeCheck("mca", GameMcaInspector, mca(), listOf(0, 4, 8, 4096, 8192, 8196))
        hugeCheck("pak", GamePakInspector, pak(), hdr)
        hugeCheck("wad", GameWadInspector, wad(), hdr)
        hugeCheck("unity", GameUnityInspector, unity(), hdr)
        hugeCheck("nbt", GameNbtInspector, nbtBytes(), hdr)
        hugeCheck("nbtgz", GameNbtInspector, gz(nbtBytes()), hdr)
        hugeCheck("pgn", GamePgnInspector, pgn(), hdr)
        hugeCheck("fen", GameFenInspector, fen(), hdr)
    }

    @Test fun deterministic() {
        for ((i, b) in listOf<Pair<FormatInspector, ByteArray>>(FontSfntInspector to ttf(), FontWoffInspector to woff(), GameNbtInspector to gz(nbtBytes()))) {
            assertEquals(insp(i, b), insp(i, b))
        }
    }
}
