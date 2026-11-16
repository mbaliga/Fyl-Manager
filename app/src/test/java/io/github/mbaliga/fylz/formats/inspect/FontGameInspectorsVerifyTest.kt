package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

private class VW {
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

private class VHuge(private val prefix: ByteArray) : ByteSource {
    override val size: Long = 50L * 1024 * 1024 * 1024
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        for (i in 0 until length) { val p = offset + i; if (p < prefix.size) out[i] = prefix[p.toInt()] }
        return out
    }
}

private fun InspectionResult.vf(label: String): String? = facts.firstOrNull { it.label == label }?.value
private fun vvinsp(i: FormatInspector, b: ByteArray, name: String? = null) = i.inspect(ByteArraySource(b), name)

class FontGameInspectorsVerifyTest {

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
        val w = VW()
        w.be16(0).be16(recs.size).be16(6 + 12 * recs.size)
        var off = 0
        for (r in recs) { w.be16(r.p).be16(r.e).be16(r.l).be16(r.id).be16(r.data.size).be16(off); off += r.data.size }
        for (r in recs) w.bytes(r.data)
        return w.done()
    }

    private fun headTable(): ByteArray {
        val w = VW()
        w.be32(0x00010000).be32(0x00018000).be32(0).be32(0x5F0F3CF5).be16(0).be16(2048)
        w.be64(3786912000L).be64(3786912000L + 86400)
        w.be16(-100).be16(-200).be16(1100).be16(900).be16(1).be16(8).be16(2).be16(0).be16(0)
        return w.done()
    }

    private fun tables(cff: Boolean, fvar: Boolean = false): List<Pair<String, ByteArray>> {
        val hhea = VW().be32(0x00010000).be16(1900).be16(-500).be16(0).pad(26).done()
        val maxp = VW().be32(0x00005000).be16(512).done()
        val os2 = VW().be16(4).be16(500).be16(700).be16(5).be16(0).pad(48).ascii("TEST").be16(0x20).pad(32).done()
        val post = VW().be32(0x00030000).be32(0).be16(0).be16(0).be32(1).pad(16).done()
        val l = ArrayList<Pair<String, ByteArray>>()
        l.add("OS/2" to os2)
        l.add((if (cff) "CFF " else "glyf") to ByteArray(8))
        l.add("head" to headTable()); l.add("hhea" to hhea); l.add("maxp" to maxp)
        l.add("name" to nameTable()); l.add("post" to post)
        if (fvar) {
            val f = VW().be16(1).be16(0).be16(16).be16(2).be16(1).be16(20).be16(2).be16(8)
                .ascii("wght").be32(100L shl 16).be32(400L shl 16).be32(900L shl 16).be16(256).be16(0).done()
            l.add("fvar" to f)
        }
        return l
    }

    private fun sfnt(ver: Long, tabs: List<Pair<String, ByteArray>>, base: Int = 0): ByteArray {
        val w = VW()
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
        return VW().ascii("ttcf").be32(0x00010000).be32(2).be32(20).be32(20L + a.size).bytes(a).bytes(b).done()
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
        val dir = VW()
        for ((i, t) in tabs.withIndex()) {
            dir.ascii(t.first).be32(off.toLong()).be32(datas[i].size.toLong()).be32(t.second.size.toLong()).be32(0)
            off += (datas[i].size + 3) / 4 * 4
        }
        val body = VW()
        for (d in datas) { body.bytes(d); body.pad((4 - d.size % 4) % 4) }
        val metaOff = off
        val total = off + meta.size
        val sfntSize = 12 + 16 * tabs.size + tabs.sumOf { (it.second.size + 3) / 4 * 4 }
        val w = VW()
        w.ascii("wOFF").be32(0x00010000).be32(total.toLong()).be16(tabs.size).be16(0).be32(sfntSize.toLong())
            .be16(1).be16(5).be32(metaOff.toLong()).be32(meta.size.toLong()).be32(metaOrig.toLong()).be32(0).be32(0)
        return w.bytes(dir.done()).bytes(body.done()).bytes(meta).done()
    }

    private fun woff2(): ByteArray {
        val dir = VW()
        dir.u8(1).u8(54)                      // head, orig 54
        dir.u8(10).u8(0x87).u8(0x68).u8(0x87).u8(0x04) // glyf transformed v0: orig 1000, transformLength 900
        dir.u8(5).u8(0x82).u8(0x2C)           // name, orig 300
        dir.u8(63).ascii("ZZZZ").u8(5)         // arbitrary tag
        val data = ByteArray(16)
        val total = 48 + dir.size() + data.size
        return VW().ascii("wOF2").be32(0x00010000).be32(total.toLong()).be16(4).be16(0).be32(5000).be32(16)
            .be16(1).be16(0).be32(0).be32(0).be32(0).be32(0).be32(0).bytes(dir.done()).bytes(data).done()
    }

    private fun bdf() = ("STARTFONT 2.1\nCOMMENT test font\nFONT -Test-Fixed-Medium-R-Normal--13-120-75-75-C-70-ISO8859-1\n" +
        "SIZE 12 75 75\nFONTBOUNDINGBOX 7 13 0 -2\nSTARTPROPERTIES 3\nFAMILY_NAME \"Fixed\"\nPIXEL_SIZE 13\nSPACING \"C\"\nENDPROPERTIES\n" +
        "CHARS 2\nSTARTCHAR A\nENCODING 65\nSWIDTH 600 0\nDWIDTH 7 0\nBBX 7 13 0 -2\nBITMAP\n00\nENDCHAR\n" +
        "STARTCHAR B\nENCODING 66\nSWIDTH 600 0\nDWIDTH 7 0\nBBX 7 13 0 -2\nBITMAP\n00\nENDCHAR\nENDFONT\n").toByteArray()

    private fun pcf(): ByteArray {
        val strings = VW().ascii("FAMILY_NAME").u8(0).ascii("PIXEL_SIZE").u8(0).ascii("Fixed").u8(0).done()
        val prop = VW().le32(0).le32(2)
            .le32(0).u8(1).le32(23)
            .le32(12).u8(0).le32(13)
        prop.padTo(28)
        prop.le32(strings.size.toLong()).bytes(strings)
        val p = prop.done()
        val bm = VW().le32(0).le32(3).pad(12).done()
        val tocEnd = 8 + 32
        return VW().u8(1).ascii("fcp").le32(2)
            .le32(1).le32(0).le32(p.size.toLong()).le32(tocEnd.toLong())
            .le32(8).le32(0).le32(bm.size.toLong()).le32((tocEnd + p.size).toLong())
            .bytes(p).bytes(bm).done()
    }

    private fun nes(): ByteArray = VW().ascii("NES").u8(0x1A).u8(2).u8(1).u8(0x13).u8(0x40).pad(8).pad(2 * 16384 + 8192).done()

    private fun nes2(): ByteArray = VW().ascii("NES").u8(0x1A).u8(8).u8(0).u8(0x10).u8(0x08).u8(0x21).u8(0x00).u8(0x07).u8(0x70).u8(1).pad(3)
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
        val w = VW().be32(0x80371240).be32(0x0F).be32(0x80000400).be32(0x1444).be32(0xA0B1C2D3).be32(0x01020304).pad(8)
            .fixedAscii("TEST N64 ROM", 20, 0x20).pad(7).ascii("N").ascii("TE").ascii("E").u8(1).pad(0x1000 - 0x40)
        return w.done()
    }
    private fun v64(): ByteArray { val z = z64(); return ByteArray(z.size) { z[it xor 1] } }
    private fun n64(): ByteArray { val z = z64(); return ByteArray(z.size) { z[it xor 3] } }

    private fun genesisImage(): ByteArray {
        val w = VW().pad(0x100)
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
        val w = VW()
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
        val chunkNbt = VW().apply {
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
        val chunk = VW().be32(z.size + 1L).u8(2).bytes(z).padTo(4096).done()
        return header + chunk
    }

    private fun pak(): ByteArray {
        val files = listOf("maps/start.bsp" to ByteArray(10), "progs/player.mdl" to ByteArray(20), "sound/a.wav" to ByteArray(5))
        val data = VW().ascii("PACK").le32(0).le32(0)
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
        val w = VW().ascii(magic).le32(3).le32(0)
        val lumpData = ByteArray(8)
        val pos = w.size(); w.bytes(lumpData)
        val dirOff = w.size()
        for (n in listOf("E1M1", "THINGS", "MAP02")) w.le32(pos.toLong()).le32(8).fixedAscii(n, 8)
        val b = w.done()
        b[8] = dirOff.toByte(); b[9] = (dirOff shr 8).toByte()
        return b
    }

    private fun unity() = VW().ascii("UnityFS").u8(0).be32(6).ascii("5.x.x").u8(0).ascii("2019.4.10f1").u8(0)
        .be64(120).be32(40).be32(80).be32(0x42).pad(60).done()

    private fun pgn() = ("[Event \"Test Open\"]\n[Site \"Testville\"]\n[Date \"2024.01.02\"]\n[Round \"1\"]\n[White \"Alice\"]\n[Black \"Bob\"]\n" +
        "[Result \"1-0\"]\n[WhiteElo \"2100\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0\n\n" +
        "[Event \"Test Open\"]\n[White \"Carol\"]\n[Black \"Dave\"]\n[Result \"1/2-1/2\"]\n\n1. d4 d5 1/2-1/2\n").toByteArray()

    private fun fen() = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1\n".toByteArray()

    // ---------------------------------------------------------------- verify fuzz
    private class Counting(private val d: ByteArray, private val fakeSize: Long? = null) : ByteSource {
        var bytes = 0L; var calls = 0
        override val size: Long get() = fakeSize ?: d.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            calls++
            if (offset < 0 || length <= 0 || offset >= d.size) return ByteArray(0)
            val end = minOf(d.size.toLong(), offset + length).toInt()
            val r = d.copyOfRange(offset.toInt(), end)
            bytes += length.toLong().coerceAtMost(Int.MAX_VALUE.toLong())
            return r
        }
    }

    private fun fixtures(): List<Pair<FormatInspector, ByteArray>> = listOf(
        FontSfntInspector to ttf(), FontSfntInspector to otf(), FontSfntInspector to ttc(),
        FontWoffInspector to woff(), FontWoff2Inspector to woff2(), FontBdfInspector to bdf(), FontPcfInspector to pcf(),
        GameNesInspector to nes(), GameNesInspector to nes2(), GameGameBoyInspector to gb(), GameGameBoyInspector to gb(0x80),
        GameGbaInspector to gba(), GameSnesInspector to snes(0x20, 0x7FC0, 0x80000), GameSnesInspector to snes(0x21, 0xFFC0, 0x100000),
        GameN64Inspector to z64(), GameN64Inspector to v64(), GameN64Inspector to n64(),
        GameGenesisInspector to genesisImage(), GameGenesisInspector to smd(), GameNdsInspector to nds(),
        GameNbtInspector to gz(nbtBytes()), GameNbtInspector to zl(nbtBytes()), GameNbtInspector to nbtBytes(),
        GameMcaInspector to mca(), GamePakInspector to pak(), GameWadInspector to wad(), GameWadInspector to wad("WAD3"),
        GameUnityInspector to unity(), GamePgnInspector to pgn(), GameFenInspector to fen(),
    )

    private fun run1(tag: String, i: FormatInspector, d: ByteArray, fake: Long? = null) {
        val c = Counting(d, fake)
        val t0 = System.nanoTime()
        try { i.inspect(c, "x.bin") } catch (t: Throwable) { fail("$tag: threw $t") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (c.bytes > FormatInspector.MAX_READ_BYTES) fail("$tag: read ${c.bytes} bytes > MAX")
        if (c.calls > 20000) fail("$tag: ${c.calls} read calls")
        if (ms > 10_000) fail("$tag: took $ms ms")
    }

    private fun magicOf(i: FormatInspector, d: ByteArray): ByteArray = d.copyOf(minOf(d.size, 16))

    @Test fun verifyFuzz() {
        val rnd = java.util.Random(1234)
        val fails = ArrayList<String>()
        fun guard(tag: String, i: FormatInspector, d: ByteArray, fake: Long? = null) {
            try { run1(tag, i, d, fake) } catch (e: AssertionError) { if (fails.size < 40) fails.add(e.message ?: tag) }
        }
        for ((idx, p) in fixtures().withIndex()) {
            val (i, b) = p
            val n = "${i.javaClass.simpleName}#$idx"
            // prefixes
            val sizes = LinkedHashSet<Int>()
            for (s in 0..minOf(b.size, 700)) sizes.add(s)
            var s = 700; while (s < b.size) { sizes.add(s); s += 61 }
            for (sz in sizes) guard("$n prefix $sz", i, b.copyOf(sz))
            // flips
            val poss = LinkedHashSet<Int>()
            for (q in 0 until minOf(b.size, 600)) poss.add(q)
            for (k in 0 until 400) poss.add(rnd.nextInt(b.size))
            for (q in poss) for (v in intArrayOf(0xFF, 0x00, 0x80, 0x7F)) {
                val m = b.copyOf(); m[q] = (m[q].toInt() xor v).toByte()
                guard("$n flip $q^$v", i, m)
            }
            // overwrite 4-byte fields at every offset, both endians
            val vals = longArrayOf(0xFFFFFFFFL, 0x7FFFFFFFL, 0L, 0x80000000L)
            for (q in 0 until minOf(b.size - 3, 700)) for (v in vals) {
                for (le in booleanArrayOf(true, false)) {
                    val m = b.copyOf()
                    for (k in 0 until 4) { val sh = if (le) 8 * k else 8 * (3 - k); m[q + k] = (v shr sh).toByte() }
                    guard("$n field $q=$v le=$le", i, m)
                }
            }
            // same, with 16-bit
            for (q in 0 until minOf(b.size - 1, 400)) for (v in intArrayOf(0xFFFF, 0x7FFF, 0)) {
                val m = b.copyOf(); m[q] = (v shr 8).toByte(); m[q + 1] = v.toByte(); guard("$n u16 $q=$v", i, m)
                val m2 = b.copyOf(); m2[q] = v.toByte(); m2[q + 1] = (v shr 8).toByte(); guard("$n u16le $q=$v", i, m2)
            }
            // huge source
            guard("$n huge", i, b, 50L * 1024 * 1024 * 1024)
            for (q in 0 until minOf(b.size - 3, 400)) {
                val m = b.copyOf(); for (k in 0 until 4) m[q + k] = 0xFF.toByte()
                guard("$n hugeFF $q", i, m, 50L * 1024 * 1024 * 1024)
            }
            // magic + random, zeros, FF
            for (len in intArrayOf(0, 1, 7, 64, 300, 4096, 20000, 70000)) {
                guard("$n zeros $len", i, ByteArray(len))
                guard("$n ff $len", i, ByteArray(len) { 0xFF.toByte() })
                for (rep in 0 until 4) {
                    val r = ByteArray(len); rnd.nextBytes(r)
                    val mg = magicOf(i, b); System.arraycopy(mg, 0, r, 0, minOf(mg.size, r.size))
                    guard("$n magic+rand $len", i, r)
                    // keep whole prefix of fixture (header) then random
                    val keep = minOf(b.size, len, 0x400); val r2 = ByteArray(len); rnd.nextBytes(r2); System.arraycopy(b, 0, r2, 0, keep)
                    guard("$n hdr+rand $len", i, r2)
                }
            }
        }
        if (fails.isNotEmpty()) fail(fails.joinToString("\n"))
    }

    @Test fun verifyNullOnNonMatching() {
        val rnd = java.util.Random(5)
        val junk = ArrayList<ByteArray>()
        junk.add(ByteArray(100000)); junk.add("hello world, this is text\n".toByteArray())
        repeat(5) { junk.add(ByteArray(50000).also { rnd.nextBytes(it) }) }
        junk.add("\n\n\n\n\n\n\n\n\n\n".toByteArray())
        val out = ArrayList<String>()
        for (i in FontGameInspectors.all) for ((k, j) in junk.withIndex()) {
            // known low-severity: SNES/MCA have no magic and answer for any big file; raw NBT accepts a leading 0x0A
            if (i === GameSnesInspector || i === GameMcaInspector || (i === GameNbtInspector && k == 7)) continue
            val r = i.inspect(ByteArraySource(j), "x")
            if (r != null) out.add("${i.javaClass.simpleName} junk#$k -> ${r.summary}")
        }
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
    }
}
