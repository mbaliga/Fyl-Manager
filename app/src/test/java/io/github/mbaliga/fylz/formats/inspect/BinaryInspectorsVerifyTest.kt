package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class VBuf(n: Int) {
    val a = ByteArray(n)
    fun u8(o: Int, v: Int) = apply { a[o] = v.toByte() }
    fun le16(o: Int, v: Int) = apply { a[o] = v.toByte(); a[o + 1] = (v shr 8).toByte() }
    fun le32(o: Int, v: Long) = apply { for (i in 0 until 4) a[o + i] = (v shr (8 * i)).toByte() }
    fun le64(o: Int, v: Long) = apply { for (i in 0 until 8) a[o + i] = (v shr (8 * i)).toByte() }
    fun be16(o: Int, v: Int) = apply { a[o] = (v shr 8).toByte(); a[o + 1] = v.toByte() }
    fun be32(o: Int, v: Long) = apply { for (i in 0 until 4) a[o + i] = (v shr (24 - 8 * i)).toByte() }
    fun str(o: Int, s: String) = apply { for (i in s.indices) a[o + i] = s[i].code.toByte() }
    fun put(o: Int, b: ByteArray) = apply { System.arraycopy(b, 0, a, o, b.size) }
}

private class CountingSource(private val data: ByteArray, private val virtualSize: Long = -1) : ByteSource {
    var requested = 0L
    var calls = 0
    var maxMs = 0L
    override val size: Long get() = if (virtualSize >= 0) virtualSize else data.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        val t0 = System.nanoTime()
        calls++
        if (length > 0) requested += length
        val r: ByteArray = if (offset < 0 || offset >= size || length <= 0) ByteArray(0) else {
            val end = minOf(size, offset + length)
            val out = ByteArray((end - offset).toInt())
            if (offset < data.size) {
                val n = minOf(out.size.toLong(), data.size - offset).toInt()
                System.arraycopy(data, offset.toInt(), out, 0, n)
            }
            out
        }
        maxMs = maxOf(maxMs, (System.nanoTime() - t0) / 1_000_000)
        return r
    }
}

class BinaryInspectorsVerifyTest {
    private fun elf64(symtab: Boolean = false): ByteArray {
        val total = 0x340 + if (symtab) 64 else 0
        val b = VBuf(total)
        b.put(0, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0, 0))
        b.le16(16, 3).le16(18, 62).le32(20, 1).le64(24, 0x1040).le64(32, 64).le64(40, 0x280)
        b.le16(52, 64).le16(54, 56).le16(56, 5).le16(58, 64).le16(60, if (symtab) 4 else 3).le16(62, 2)
        fun ph(i: Int, type: Long, flags: Long, off: Long, vaddr: Long, filesz: Long) {
            val o = 64 + i * 56
            b.le32(o, type).le32(o + 4, flags).le64(o + 8, off).le64(o + 16, vaddr).le64(o + 32, filesz).le64(o + 40, filesz)
        }
        ph(0, 1, 5, 0, 0x400000, total.toLong())
        ph(1, 3, 4, 0x160, 0x400160, 28)
        ph(2, 2, 6, 0x200, 0x400200, 96)
        ph(3, 4, 4, 0x180, 0x400180, 36)
        ph(4, 0x6474e551, 6, 0, 0, 0)
        b.str(0x160, "/lib64/ld-linux-x86-64.so.2")
        b.le32(0x180, 4).le32(0x184, 20).le32(0x188, 3).str(0x18C, "GNU")
        for (i in 0 until 20) b.u8(0x190 + i, i)
        b.str(0x1C1, "libc.so.6"); b.str(0x1CB, "libm.so.6"); b.str(0x1D5, "libfoo.so.1")
        fun dyn(i: Int, tag: Long, v: Long) { b.le64(0x200 + i * 16, tag).le64(0x208 + i * 16, v) }
        dyn(0, 1, 1); dyn(1, 1, 11); dyn(2, 14, 21); dyn(3, 5, 0x4001C0); dyn(4, 10, 33); dyn(5, 0, 0)
        b.str(0x261, ".text"); b.str(0x267, ".shstrtab")
        b.le32(0x280 + 64, 1).le32(0x280 + 64 + 4, 1)
        b.le32(0x280 + 128, 7).le32(0x280 + 128 + 4, 3).le64(0x280 + 128 + 24, 0x260).le64(0x280 + 128 + 32, 17)
        if (symtab) b.le32(0x280 + 192 + 4, 2)
        return b.a
    }
    private fun pe(dotnet: Boolean = false): ByteArray {
        val b = VBuf(0x600)
        b.str(0, "MZ").le32(0x3C, 0x80)
        b.str(0x80, "PE").le16(0x84, 0x8664).le16(0x86, 2).le32(0x88, 1600000000).le16(0x94, 240).le16(0x96, 0x2022)
        val o = 0x98
        b.le16(o, 0x20b).u8(o + 2, 14).u8(o + 3, 29).le32(o + 16, 0x1000).le64(o + 24, 0x180000000L)
        b.le16(o + 40, 6).le16(o + 42, 0).le16(o + 48, 6).le16(o + 50, 0).le32(o + 56, 0x3000).le32(o + 64, 0xABCD)
        b.le16(o + 68, 2).le16(o + 70, 0x8160).le32(o + 108, 16)
        val dirs = o + 112
        b.le32(dirs, 0x2040).le32(dirs + 4, 40)             // export
        b.le32(dirs + 8, 0x2000).le32(dirs + 12, 60)        // import
        if (dotnet) { b.le32(dirs + 14 * 8, 0x2140).le32(dirs + 14 * 8 + 4, 72) }
        val s = o + 240
        b.str(s, ".text").le32(s + 8, 0x100).le32(s + 12, 0x1000).le32(s + 16, 0x200).le32(s + 20, 0x200).le32(s + 36, 0x60000020)
        b.str(s + 40, ".rdata").le32(s + 48, 0x200).le32(s + 52, 0x2000).le32(s + 56, 0x200).le32(s + 60, 0x400).le32(s + 76, 0x40000040)
        // .rdata at file 0x400 == RVA 0x2000
        b.le32(0x400 + 12, 0x2100).le32(0x400 + 16, 0x2180)
        b.le32(0x400 + 20 + 12, 0x2110).le32(0x400 + 20 + 16, 0x2190)
        b.le32(0x440 + 12, 0x2120).le32(0x440 + 20, 3).le32(0x440 + 24, 2)
        b.str(0x500, "KERNEL32.dll"); b.str(0x510, "USER32.dll"); b.str(0x520, "mylib.dll")
        if (dotnet) {
            b.le32(0x540, 72).le16(0x544, 2).le16(0x546, 5).le32(0x548, 0x2190).le32(0x54C, 0x40).le32(0x550, 1)
            b.le32(0x590, 0x424A5342).le16(0x594, 1).le16(0x596, 1).le32(0x59C, 12).str(0x5A0, "v4.0.30319")
        }
        return b.a
    }
    private fun machoThin64(): ByteArray {
        val cmds = 72 + 24 + 24 + 56 + 16
        val b = VBuf(32 + cmds)
        b.put(0, byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()))
        b.le32(4, 0x0100000C).le32(8, 0).le32(12, 2).le32(16, 5).le32(20, cmds.toLong()).le32(24, 0x200085)
        var p = 32
        b.le32(p, 0x19).le32(p + 4, 72).str(p + 8, "__TEXT"); p += 72
        b.le32(p, 0x1B).le32(p + 4, 24); for (i in 0 until 16) b.u8(p + 8 + i, i + 1); p += 24
        b.le32(p, 0x32).le32(p + 4, 24).le32(p + 8, 1).le32(p + 12, 0x000E0000).le32(p + 16, 0x000E0400); p += 24
        b.le32(p, 0xC).le32(p + 4, 56).le32(p + 8, 24).le32(p + 16, 0x04E80000).str(p + 24, "/usr/lib/libSystem.B.dylib"); p += 56
        b.le32(p, 0x1D).le32(p + 4, 16)
        return b.a
    }

    private fun machoBe32Dylib(): ByteArray {
        val b = VBuf(28 + 48)
        b.put(0, byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()))
        b.be32(4, 18).be32(8, 0).be32(12, 6).be32(16, 1).be32(20, 48).be32(24, 0)
        b.be32(28, 0xD).be32(32, 48).be32(36, 24).be32(44, 0x00010200).str(52, "@rpath/libx.dylib")
        return b.a
    }

    private fun machoFat(): ByteArray {
        val b = VBuf(0x2000 + 32)
        b.be32(0, 0xCAFEBABEL).be32(4, 2)
        b.be32(8, 0x01000007).be32(12, 3).be32(16, 0x1000).be32(20, 32).be32(24, 12)
        b.be32(28, 0x0100000C).be32(32, 0).be32(36, 0x2000).be32(40, 32).be32(44, 14)
        for (off in intArrayOf(0x1000, 0x2000)) {
            b.put(off, byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()))
            b.le32(off + 4, if (off == 0x1000) 0x01000007L else 0x0100000CL).le32(off + 12, 6)
        }
        return b.a
    }
    private fun javaClass8(): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(o)
        d.writeInt(0xCAFEBABE.toInt()); d.writeShort(0); d.writeShort(52); d.writeShort(16)
        fun utf(s: String) { d.writeByte(1); d.writeUTF(s) }
        utf("com/example/Hello"); d.writeByte(7); d.writeShort(1)       // 1, 2
        utf("java/lang/Object"); d.writeByte(7); d.writeShort(3)        // 3, 4
        utf("java/lang/Runnable"); d.writeByte(7); d.writeShort(5)      // 5, 6
        utf("SourceFile"); utf("Hello.java"); utf("count"); utf("I")    // 7..10
        utf("run"); utf("()V")                                          // 11, 12
        d.writeByte(5); d.writeLong(42L)                                // 13 (+14)
        d.writeByte(3); d.writeInt(7)                                   // 15
        d.writeShort(0x0021); d.writeShort(2); d.writeShort(4)
        d.writeShort(1); d.writeShort(6)
        d.writeShort(1); d.writeShort(2); d.writeShort(9); d.writeShort(10); d.writeShort(0)
        d.writeShort(1); d.writeShort(1); d.writeShort(11); d.writeShort(12); d.writeShort(0)
        d.writeShort(1); d.writeShort(7); d.writeInt(2); d.writeShort(8)
        return o.toByteArray()
    }
    private fun dex(): ByteArray {
        val b = VBuf(128)
        b.str(0, "dex\n039")
        b.le32(32, 128).le32(36, 112).le32(40, 0x12345678).le32(52, 112)
        b.le32(56, 7).le32(60, 112).le32(64, 3).le32(68, 112).le32(72, 2).le32(76, 112)
        b.le32(80, 1).le32(84, 112).le32(88, 5).le32(92, 112).le32(96, 1).le32(100, 112).le32(104, 16).le32(108, 112)
        val sha = java.security.MessageDigest.getInstance("SHA-1").digest(b.a.copyOfRange(32, 128))
        b.put(12, sha)
        val ad = java.util.zip.Adler32(); ad.update(b.a, 12, 116)
        b.le32(8, ad.value)
        return b.a
    }
    private fun leb(v: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream(); var x = v
        do { var by = (x and 0x7F).toInt(); x = x ushr 7; if (x != 0L) by = by or 0x80; o.write(by) } while (x != 0L)
        return o.toByteArray()
    }
    private fun wsec(id: Int, body: ByteArray) = byteArrayOf(id.toByte()) + leb(body.size.toLong()) + body
    private fun wstr(s: String) = leb(s.length.toLong()) + s.toByteArray()

    private fun wasm(): ByteArray {
        val head = byteArrayOf(0, 0x61, 0x73, 0x6D, 1, 0, 0, 0)
        return head +
            wsec(1, byteArrayOf(1, 0x60, 1, 0x7F, 0)) +
            wsec(2, byteArrayOf(1) + wstr("env") + wstr("log") + byteArrayOf(0, 0)) +
            wsec(3, byteArrayOf(1, 0)) +
            wsec(5, byteArrayOf(1, 0, 1)) +
            wsec(7, byteArrayOf(2) + wstr("memory") + byteArrayOf(2, 0) + wstr("run") + byteArrayOf(0, 1)) +
            wsec(10, byteArrayOf(1, 2, 0, 0x0B)) +
            wsec(0, wstr("name") + byteArrayOf(1, 2))
    }
    private fun svarint(v: Long): ByteArray {
        if (v < 128) return byteArrayOf(v.toByte())
        val parts = ArrayList<Int>(); var x = v
        parts.add((x and 0x7F).toInt()); x = x shr 7
        while (x > 0) { parts.add(((x and 0x7F) or 0x80).toInt()); x = x shr 7 }
        return parts.reversed().map { it.toByte() }.toByteArray()
    }

    private fun record(type: String, name: String, tbl: String, root: Int, sql: String): ByteArray {
        val texts = listOf(type, name, tbl)
        val types = ArrayList<ByteArray>()
        for (t in texts) types += svarint(13L + 2 * t.length)
        types += svarint(1)
        types += svarint(13L + 2 * sql.length)
        val hdrLen = 1 + types.sumOf { it.size }
        val o = java.io.ByteArrayOutputStream()
        o.write(hdrLen); for (t in types) o.write(t)
        for (t in texts) o.write(t.toByteArray())
        o.write(root); o.write(sql.toByteArray())
        return o.toByteArray()
    }

    private fun leafCell(rowid: Long, payload: ByteArray) = svarint(payload.size.toLong()) + svarint(rowid) + payload

    private fun leafPage(pageSize: Int, hdr: Int, cells: List<ByteArray>): ByteArray {
        val p = VBuf(pageSize)
        p.u8(hdr, 0x0D).be16(hdr + 3, cells.size)
        var end = pageSize
        for ((i, c) in cells.withIndex()) { end -= c.size; p.put(end, c); p.be16(hdr + 8 + 2 * i, end) }
        p.be16(hdr + 5, end)
        return p.a
    }

    private val usersSql = "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)"
    private val postsSql = "CREATE TABLE posts(id INTEGER, user_id INT, body TEXT)"
    private val idxSql = "CREATE INDEX idx_users_name ON users(name)"

    private fun fillHeader(p: ByteArray, pages: Int) {
        val b = VBuf(0)
        System.arraycopy("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1), 0, p, 0, 16)
        fun be16(o: Int, v: Int) { p[o] = (v shr 8).toByte(); p[o + 1] = v.toByte() }
        fun be32(o: Int, v: Long) { for (i in 0 until 4) p[o + i] = (v shr (24 - 8 * i)).toByte() }
        be16(16, 1024); p[18] = 1; p[19] = 1; p[21] = 64; p[22] = 32; p[23] = 32
        be32(24, 7); be32(28, pages.toLong()); be32(36, 2); be32(40, 5); be32(44, 4); be32(56, 1); be32(60, 42)
        be32(68, 0x47504B47); be32(92, 7); be32(96, 3045000)
        assertNotNull(b)
    }

    private fun sqliteLeaf(): ByteArray {
        val cells = listOf(
            leafCell(1, record("table", "users", "users", 2, usersSql)),
            leafCell(2, record("index", "idx_users_name", "users", 3, idxSql)),
            leafCell(3, record("table", "posts", "posts", 4, postsSql)),
        )
        val db = ByteArray(4096)
        System.arraycopy(leafPage(1024, 100, cells), 0, db, 0, 1024)
        fillHeader(db, 4)
        return db
    }

    private fun sqliteInterior(): ByteArray {
        val db = ByteArray(3 * 1024)
        val p1 = VBuf(1024)
        p1.u8(100, 0x05).be16(100 + 3, 1).be32(100 + 8, 3)
        val cell = VBuf(5).be32(0, 2).u8(4, 1).a
        p1.put(1024 - 5, cell).be16(100 + 12, 1024 - 5)
        System.arraycopy(p1.a, 0, db, 0, 1024)
        System.arraycopy(leafPage(1024, 0, listOf(leafCell(1, record("table", "users", "users", 2, usersSql)))), 0, db, 1024, 1024)
        System.arraycopy(leafPage(1024, 0, listOf(leafCell(2, record("table", "posts", "posts", 3, postsSql)))), 0, db, 2048, 1024)
        fillHeader(db, 3)
        return db
    }
    private fun pyc311(): ByteArray {
        val b = VBuf(17)
        b.put(0, byteArrayOf(0xA7.toByte(), 0x0D, 0x0D, 0x0A)).le32(4, 0).le32(8, 1600000000).le32(12, 1234).u8(16, 0xE3)
        return b.a
    }

    private class Case(val name: String, val insp: FormatInspector, val data: ByteArray)

    private fun cases(): List<Case> = listOf(
        Case("elf", BinaryElfInspector, elf64(true)),
        Case("elf-nosym", BinaryElfInspector, elf64(false)),
        Case("pe", BinaryPeInspector, pe(false)),
        Case("pe-net", BinaryPeInspector, pe(true)),
        Case("macho", BinaryMachOInspector, machoThin64()),
        Case("macho-be", BinaryMachOInspector, machoBe32Dylib()),
        Case("macho-fat", BinaryMachOInspector, machoFat()),
        Case("class", BinaryClassInspector, javaClass8()),
        Case("dex", BinaryDexInspector, dex()),
        Case("wasm", BinaryWasmInspector, wasm()),
        Case("sqlite", BinarySqliteInspector, sqliteLeaf()),
        Case("sqlite-int", BinarySqliteInspector, sqliteInterior()),
        Case("pyc", BinaryPycInspector, pyc311()),
    )

    private fun check(c: Case, data: ByteArray, what: String, vsize: Long = -1) {
        val src = CountingSource(data, vsize)
        val t0 = System.currentTimeMillis()
        try { c.insp.inspect(src, "x") } catch (t: Throwable) { throw AssertionError("${c.name} $what: threw $t", t) }
        val ms = System.currentTimeMillis() - t0
        assertTrue("${c.name} $what: requested ${src.requested}", src.requested <= FormatInspector.MAX_READ_BYTES)
        assertTrue("${c.name} $what: calls ${src.calls}", src.calls <= 20000)
        assertTrue("${c.name} $what: took $ms ms", ms < 2000 && src.maxMs < 2000)
    }

    @Test fun prefixes() { for (c in cases()) for (n in 0..c.data.size) check(c, c.data.copyOf(n), "prefix $n") }

    @Test fun byteFlips() {
        val rnd = java.util.Random(7)
        for (c in cases()) {
            val step = maxOf(1, c.data.size / 1500)
            var i = 0
            while (i < c.data.size) {
                for (v in intArrayOf(0x00, 0xFF, 0x80, 0x7F, rnd.nextInt(256))) {
                    val d = c.data.copyOf(); d[i] = v.toByte(); check(c, d, "flip @$i=$v")
                }
                i += step
            }
        }
    }

    @Test fun fieldOverwrites() {
        for (c in cases()) {
            val step = if (c.data.size > 2000) 2 else 1
            var i = 0
            while (i + 4 <= c.data.size) {
                for (v in longArrayOf(0xFFFFFFFFL, 0x7FFFFFFFL, 0L, 0x80000000L, 0x00FFFFFFL)) {
                    val le = c.data.copyOf(); val be = c.data.copyOf()
                    for (k in 0 until 4) { le[i + k] = (v shr (8 * k)).toByte(); be[i + k] = (v shr (24 - 8 * k)).toByte() }
                    check(c, le, "u32le@$i=$v"); check(c, be, "u32be@$i=$v")
                }
                // 8-byte
                val d8 = c.data.copyOf(); if (i + 8 <= d8.size) { for (k in 0 until 8) d8[i + k] = 0xFF.toByte(); check(c, d8, "u64ff@$i") }
                val d7 = c.data.copyOf(); if (i + 8 <= d7.size) { for (k in 0 until 7) d7[i + k] = 0xFF.toByte(); d7[i + 7] = 0x7F; check(c, d7, "u64max@$i") }
                i += step
            }
        }
    }

    @Test fun zerosFfAndRandom() {
        val rnd = java.util.Random(99)
        val sizes = intArrayOf(0, 1, 3, 4, 7, 8, 15, 16, 31, 32, 63, 64, 100, 127, 128, 255, 512, 1000, 4096, 70000, 1_000_000)
        for (c in cases()) {
            for (n in sizes) {
                check(c, ByteArray(n), "zeros $n")
                check(c, ByteArray(n) { 0xFF.toByte() }, "ff $n")
                val mg = c.data.copyOf(minOf(c.data.size, 16))
                for (r in 0 until 5) {
                    val b = ByteArray(maxOf(n, mg.size)); rnd.nextBytes(b)
                    System.arraycopy(mg, 0, b, 0, mg.size); check(c, b, "magic+random $n")
                    val b2 = ByteArray(n); rnd.nextBytes(b2); check(c, b2, "random $n")
                }
            }
        }
    }

    @Test fun hugeVirtualFiles() {
        for (c in cases()) {
            for (vs in longArrayOf(1L shl 30, 1L shl 40, Long.MAX_VALUE / 2)) {
                check(c, c.data, "virtual $vs", vs)
                val big = c.data.copyOf(c.data.size)
                check(c, big, "virtual-ff $vs", vs)
            }
        }
    }

    @Test fun nonMatchingReturnsNull() {
        val junk = listOf(ByteArray(0), ByteArray(100), ByteArray(5000) { 0x41 }, "hello world, plain text\n".repeat(50).toByteArray())
        for (i in BinaryInspectors.all) for (j in junk) assertNull("${i.formatIds}", i.inspect(ByteArraySource(j), "a.bin"))
        // each real fixture accepted only by its own inspector (class vs macho etc.)
        for (c in cases()) {
            for (i in BinaryInspectors.all) {
                val r = i.inspect(ByteArraySource(c.data), "x")
                if (i !== c.insp) assertNull("${c.name} claimed by ${i.formatIds}", r)
                else assertNotNull("${c.name} not recognised", r)
            }
        }
    }

    @Test fun sectionCapDoesNotClaimStripped() {
        // ELF with 600 section headers, .symtab last: must not claim stripped
        val n = 600
        val b = VBuf(0x100 + 64 * n + 64)
        b.put(0, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0, 0))
        b.le16(16, 1).le16(18, 62).le64(40, 0x100).le16(52, 64).le16(58, 64).le16(60, n).le16(62, 0)
        b.le32(0x100 + 64 * (n - 1) + 4, 2)
        val r = BinaryElfInspector.inspect(ByteArraySource(b.a), "x")!!
        val st = r.facts.firstOrNull { it.label == "Stripped" }?.value
        assertTrue("claimed '$st' with symtab beyond cap; warnings=${r.warnings}", st == null || !st.startsWith("yes"))
    }

    @Test fun sqliteUnreadSchemaDoesNotClaimZeroTables() {
        val b = ByteArray(100)
        System.arraycopy("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1), 0, b, 0, 16)
        b[16] = 4; b[17] = 0; b[56 + 3] = 5 // bad encoding, page size 1024
        val r = BinarySqliteInspector.inspect(ByteArraySource(b), "x")!!
        val t = r.facts.firstOrNull { it.label == "Tables" }?.value
        assertTrue("claimed Tables=$t although schema not read", t == null)
    }
}
