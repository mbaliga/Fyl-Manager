package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class BinBuf(n: Int) {
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

private fun InspectionResult.f(label: String, group: String? = null): String? =
    facts.firstOrNull { it.label == label && (group == null || it.group == group) }?.value
private fun InspectionResult.all(label: String): List<String> = facts.filter { it.label == label }.map { it.value }

/** Records requested sizes so tests can prove reads stay bounded. */
private class BinSpy(private val data: ByteArray) : ByteSource {
    var maxRequest = 0
    var total = 0L
    override val size: Long get() = data.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        maxRequest = maxOf(maxRequest, length)
        if (offset < 0 || offset >= data.size || length <= 0) return ByteArray(0)
        val r = data.copyOfRange(offset.toInt(), minOf(data.size.toLong(), offset + length).toInt())
        total += r.size
        return r
    }
}

class BinaryInspectorsTest {

    // ------------------------------------------------------------------ helpers

    private fun run(i: FormatInspector, b: ByteArray) = i.inspect(ByteArraySource(b), null)

    private fun assertTruncationSafe(i: FormatInspector, b: ByteArray) {
        for (n in 0..b.size) {
            try { i.inspect(ByteArraySource(b.copyOf(n)), "x") } catch (t: Throwable) { throw AssertionError("threw at prefix $n: $t", t) }
        }
    }

    private fun assertBounded(i: FormatInspector, b: ByteArray) {
        val spy = BinSpy(b)
        val start = System.currentTimeMillis()
        try { i.inspect(spy, null) } catch (t: Throwable) { throw AssertionError("threw: $t", t) }
        assertTrue("took too long", System.currentTimeMillis() - start < 5000)
        assertTrue("read too much: ${spy.total}", spy.total <= FormatInspector.MAX_READ_BYTES)
        assertTrue("single read too large: ${spy.maxRequest}", spy.maxRequest <= 4 * 1024 * 1024)
    }

    // ------------------------------------------------------------------ ELF

    private fun elf64(symtab: Boolean = false): ByteArray {
        val total = 0x340 + if (symtab) 64 else 0
        val b = BinBuf(total)
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

    @Test fun elf64Facts() {
        val r = run(BinaryElfInspector, elf64())!!
        assertEquals("elf", r.formatId)
        assertEquals("64-bit", r.f("Class"))
        assertEquals("LSB (little-endian)", r.f("Byte order"))
        assertEquals("UNIX System V", r.f("OS ABI"))
        assertEquals("shared object", r.f("Type"))
        assertEquals("x86-64", r.f("Machine"))
        assertEquals("0x1040", r.f("Entry point"))
        assertEquals("5", r.f("Program headers"))
        assertEquals("/lib64/ld-linux-x86-64.so.2", r.f("Interpreter"))
        assertEquals(listOf("libc.so.6", "libm.so.6"), r.all("NEEDED"))
        assertEquals("libfoo.so.1", r.f("SONAME"))
        assertEquals("000102030405060708090a0b0c0d0e0f10111213", r.f("Build ID"))
        assertEquals("yes (no .symtab)", r.f("Stripped"))
        assertEquals("non-executable", r.f("Stack"))
        assertEquals("dynamic", r.f("Linking"))
        assertTrue(r.f("Sections")!!.contains(".text"))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun elfNotStrippedWithSymtab() {
        assertEquals("no", run(BinaryElfInspector, elf64(true))!!.f("Stripped"))
    }

    @Test fun elf32BigEndianHeader() {
        val b = BinBuf(52)
        b.put(0, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 2, 1, 0))
        b.be16(16, 2).be16(18, 20).be32(20, 1).be32(24, 0x10000074)
        val r = run(BinaryElfInspector, b.a)!!
        assertEquals("32-bit", r.f("Class"))
        assertEquals("MSB (big-endian)", r.f("Byte order"))
        assertEquals("PowerPC", r.f("Machine"))
        assertEquals("executable", r.f("Type"))
        assertEquals("0x10000074", r.f("Entry point"))
        assertTrue(r.f("Stripped")!!.startsWith("unknown"))
    }

    @Test fun elfRejectsOthers() {
        assertNull(run(BinaryElfInspector, ByteArray(100)))
        assertNull(run(BinaryElfInspector, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 9, 1)))
    }

    @Test fun elfTruncation() = assertTruncationSafe(BinaryElfInspector, elf64(true))

    @Test fun elfHostileLengths() {
        val b = elf64()
        b[56] = 0xFF.toByte(); b[57] = 0xFF.toByte()               // phnum
        val spy = b.copyOf()
        for (i in 0 until 8) spy[0x280 + 64 + 32 + i] = 0xFF.toByte() // .text size
        for (i in 0 until 8) { spy[64 + 56 * 2 + 32 + i] = 0xFF.toByte() }  // dynamic filesz
        assertBounded(BinaryElfInspector, spy)
        val c = elf64()
        for (i in 0 until 8) { c[32 + i] = 0xFF.toByte(); c[40 + i] = 0xFF.toByte() } // phoff, shoff
        assertBounded(BinaryElfInspector, c)
        val d = elf64()
        d[60] = 0; d[61] = 0                                        // shnum 0 with the real count in section 0
        for (i in 0 until 4) d[0x280 + 32 + i] = 0xFF.toByte()
        assertBounded(BinaryElfInspector, d)
        assertNotNull(run(BinaryElfInspector, d))
    }

    // ------------------------------------------------------------------ PE

    private fun pe(dotnet: Boolean = false): ByteArray {
        val b = BinBuf(0x600)
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

    @Test fun peDllFacts() {
        val r = run(BinaryPeInspector, pe())!!
        assertEquals("exe", r.formatId)
        assertEquals("x86-64 (AMD64)", r.f("Machine"))
        assertEquals("DLL (dynamic-link library)", r.f("Type"))
        assertEquals("PE32+ (64-bit)", r.f("Format"))
        assertEquals("2020-09-13T12:26:40Z (may be a build hash in reproducible builds)", r.f("Timestamp"))
        assertEquals("Windows GUI", r.f("Subsystem"))
        assertEquals("0x1000", r.f("Entry point (RVA)"))
        assertEquals("0x180000000", r.f("Image base"))
        assertEquals("2", r.f("Sections", "Header"))
        assertTrue(r.f(".text")!!.contains("virtual 256 bytes"))
        assertTrue(r.f(".text")!!.contains("R-X"))
        assertTrue(r.f(".rdata")!!.contains("raw 512 bytes"))
        assertEquals(listOf("KERNEL32.dll", "USER32.dll"), r.all("Imports"))
        assertEquals("mylib.dll", r.f("Export name"))
        assertEquals("3", r.f("Exported functions"))
        assertEquals("2", r.f("Named exports"))
        assertTrue(r.f("Characteristics")!!.contains("DLL"))
        assertTrue(r.f("DLL characteristics")!!.contains("DYNAMIC_BASE"))
        assertNull(r.f(".NET (CLI) assembly"))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun peDotNet() {
        val r = run(BinaryPeInspector, pe(true))!!
        assertEquals("yes", r.f(".NET (CLI) assembly"))
        assertEquals("2.5", r.f("CLR runtime version"))
        assertEquals("v4.0.30319", r.f("Metadata version"))
        assertTrue(r.title.contains(".NET"))
    }

    @Test fun peRejectsOthers() {
        assertNull(run(BinaryPeInspector, ByteArray(200)))
        assertNull(run(BinaryPeInspector, BinBuf(200).str(0, "MZ").le32(0x3C, 0x90).a))
    }

    @Test fun peTruncation() = assertTruncationSafe(BinaryPeInspector, pe(true))

    @Test fun peHostileLengths() {
        val b = pe()
        b.fill(0xFF.toByte(), 0x98 + 112, 0x98 + 112 + 8)           // export dir RVA+size
        b.fill(0xFF.toByte(), 0x98 + 112 + 8, 0x98 + 112 + 12)      // import RVA
        b[0x86] = 0xFF.toByte(); b[0x87] = 0xFF.toByte()            // section count
        b[0x94] = 0xFF.toByte(); b[0x95] = 0xFF.toByte()            // optional header size
        assertBounded(BinaryPeInspector, b)
        val c = pe(true)
        c.fill(0xFF.toByte(), 0x540 + 8, 0x540 + 12)                // metadata RVA
        c.fill(0xFF.toByte(), 0x590 + 12, 0x590 + 16)               // version length
        assertBounded(BinaryPeInspector, c)
        val d = pe()
        d.fill(0xFF.toByte(), 0x80 - 4, 0x80)
        d[0x3C] = 0xFF.toByte(); d[0x3D] = 0xFF.toByte(); d[0x3E] = 0xFF.toByte(); d[0x3F] = 0xFF.toByte()
        assertBounded(BinaryPeInspector, d)
    }

    // ------------------------------------------------------------------ Mach-O

    private fun machoThin64(): ByteArray {
        val cmds = 72 + 24 + 24 + 56 + 16
        val b = BinBuf(32 + cmds)
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
        val b = BinBuf(28 + 48)
        b.put(0, byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()))
        b.be32(4, 18).be32(8, 0).be32(12, 6).be32(16, 1).be32(20, 48).be32(24, 0)
        b.be32(28, 0xD).be32(32, 48).be32(36, 24).be32(44, 0x00010200).str(52, "@rpath/libx.dylib")
        return b.a
    }

    private fun machoFat(): ByteArray {
        val b = BinBuf(0x2000 + 32)
        b.be32(0, 0xCAFEBABEL).be32(4, 2)
        b.be32(8, 0x01000007).be32(12, 3).be32(16, 0x1000).be32(20, 32).be32(24, 12)
        b.be32(28, 0x0100000C).be32(32, 0).be32(36, 0x2000).be32(40, 32).be32(44, 14)
        for (off in intArrayOf(0x1000, 0x2000)) {
            b.put(off, byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()))
            b.le32(off + 4, if (off == 0x1000) 0x01000007L else 0x0100000CL).le32(off + 12, 6)
        }
        return b.a
    }

    @Test fun machoThinFacts() {
        val r = run(BinaryMachOInspector, machoThin64())!!
        assertEquals("macho", r.formatId)
        val g = "Mach-O: header"
        assertEquals("64-bit", r.f("Bitness", g))
        assertEquals("little-endian", r.f("Byte order", g))
        assertEquals("ARM64", r.f("CPU", g))
        assertEquals("executable", r.f("File type", g))
        assertEquals("5", r.f("Load commands", g))
        assertTrue(r.f("Flags", g)!!.contains("PIE"))
        assertEquals("01020304-0506-0708-090A-0B0C0D0E0F10", r.f("UUID"))
        assertEquals("macOS 14.0.0 (SDK 14.4.0)", r.f("Minimum OS"))
        assertEquals(listOf("/usr/lib/libSystem.B.dylib (current 1256.0.0)"), r.all("Dylib"))
        assertEquals("__TEXT", r.f("Segments"))
        assertEquals("present (not verified)", r.f("Code signature"))
        assertTrue(r.title, r.title.contains("ARM64"))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun machoBigEndian32() {
        val r = run(BinaryMachOInspector, machoBe32Dylib())!!
        assertEquals("32-bit", r.f("Bitness"))
        assertEquals("big-endian", r.f("Byte order"))
        assertEquals("PowerPC", r.f("CPU"))
        assertEquals("dynamic library", r.f("File type"))
        assertEquals("@rpath/libx.dylib (current 1.2.0)", r.f("Install name"))
    }

    @Test fun machoFatFacts() {
        val r = run(BinaryMachOInspector, machoFat())!!
        assertEquals("2", r.f("Architectures"))
        assertEquals(2, r.facts.count { it.label.startsWith("Slice ") && it.group == "Universal binary" })
        assertTrue(r.title, r.title.contains("x86-64") && r.title.contains("ARM64"))
        assertEquals("x86-64", r.f("CPU", "Slice 1 (x86-64): header"))
        assertEquals("ARM64", r.f("CPU", "Slice 2 (ARM64): header"))
    }

    @Test fun machoAndClassDoNotClaimEachOther() {
        assertNull(run(BinaryMachOInspector, javaClass8()))
        assertNull(run(BinaryClassInspector, machoFat()))
    }

    @Test fun machoTruncation() {
        assertTruncationSafe(BinaryMachOInspector, machoThin64())
        assertTruncationSafe(BinaryMachOInspector, machoBe32Dylib())
        val fat = machoFat()
        for (n in (0..0x1040) step 1) BinaryMachOInspector.inspect(ByteArraySource(fat.copyOf(n)), null)
    }

    @Test fun machoHostileLengths() {
        val a = machoThin64()
        a.fill(0xFF.toByte(), 16, 24)                               // ncmds, sizeofcmds
        assertBounded(BinaryMachOInspector, a)
        val b = machoThin64()
        b.fill(0xFF.toByte(), 32 + 4, 32 + 8)                       // first command size
        assertBounded(BinaryMachOInspector, b)
        val c = machoFat()
        c.fill(0xFF.toByte(), 4, 8)                                 // arch count
        assertBounded(BinaryMachOInspector, c)
        val d = machoFat()
        d.fill(0xFF.toByte(), 16, 24)                               // slice offset and size
        assertBounded(BinaryMachOInspector, d)
        val e = machoThin64()
        e.fill(0xFF.toByte(), 32 + 72 + 24 + 24 + 8, 32 + 72 + 24 + 24 + 12) // dylib name offset
        assertBounded(BinaryMachOInspector, e)
    }

    // ------------------------------------------------------------------ Java class

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

    @Test fun classFacts() {
        val r = run(BinaryClassInspector, javaClass8())!!
        assertEquals("class", r.formatId)
        assertEquals("52.0", r.f("Class file version"))
        assertEquals("Java 8", r.f("Java version"))
        assertEquals("15", r.f("Constant pool entries"))
        assertEquals("com.example.Hello", r.f("This class"))
        assertEquals("java.lang.Object", r.f("Super class"))
        assertEquals("java.lang.Runnable", r.f("Implements / extends"))
        assertEquals("1", r.f("Interfaces"))
        assertEquals("1", r.f("Fields"))
        assertEquals("1", r.f("Methods"))
        assertEquals("Hello.java", r.f("Source file"))
        assertTrue(r.f("Access flags")!!.contains("public super"))
        assertEquals("class", r.f("Kind"))
        assertTrue(r.title, r.title.startsWith("com.example.Hello"))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun classVersionMapping() {
        assertEquals("1.1", BinaryClassInspector.javaVersion(45))
        assertEquals("1.4", BinaryClassInspector.javaVersion(48))
        assertEquals("5", BinaryClassInspector.javaVersion(49))
        assertEquals("8", BinaryClassInspector.javaVersion(52))
        assertEquals("17", BinaryClassInspector.javaVersion(61))
        assertEquals("21", BinaryClassInspector.javaVersion(65))
        assertNull(BinaryClassInspector.javaVersion(44))
    }

    @Test fun classRejectsOthers() {
        assertNull(run(BinaryClassInspector, ByteArray(40)))
    }

    @Test fun classTruncation() = assertTruncationSafe(BinaryClassInspector, javaClass8())

    @Test fun classHostileLengths() {
        val a = javaClass8()
        a[8] = 0xFF.toByte(); a[9] = 0xFF.toByte()                  // pool count 65535
        assertBounded(BinaryClassInspector, a)
        val b = javaClass8()
        val tail = b.size - 8                                        // class attribute length -> 0xFFFFFFFF
        b.fill(0xFF.toByte(), tail - 4, tail)
        assertBounded(BinaryClassInspector, b)
        val c = javaClass8()
        c[10] = 1; c[11] = 0xFF.toByte(); c[12] = 0xFF.toByte()      // first Utf8 length 65535
        assertBounded(BinaryClassInspector, c)
        assertNotNull(run(BinaryClassInspector, c))
    }

    // ------------------------------------------------------------------ DEX

    private fun dex(): ByteArray {
        val b = BinBuf(128)
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

    @Test fun dexFacts() {
        val r = run(BinaryDexInspector, dex())!!
        assertEquals("dex", r.formatId)
        assertEquals("039", r.f("Version"))
        assertEquals("128 bytes", r.f("Declared file size"))
        assertEquals("112 bytes", r.f("Header size"))
        assertEquals("little-endian (standard)", r.f("Endianness tag"))
        assertEquals("7", r.f("Strings"))
        assertEquals("3", r.f("Types"))
        assertEquals("2", r.f("Prototypes"))
        assertEquals("1", r.f("Fields"))
        assertEquals("5", r.f("Methods"))
        assertEquals("1", r.f("Classes"))
        assertEquals("yes", r.f("Checksum valid"))
        assertEquals("yes", r.f("Signature valid"))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun dexDetectsCorruption() {
        val b = dex(); b[120] = 0x55
        val r = run(BinaryDexInspector, b)!!
        assertEquals("no", r.f("Checksum valid"))
        assertTrue(r.warnings.any { it.contains("checksum") })
    }

    @Test fun dexRejectsOthers() {
        assertNull(run(BinaryDexInspector, ByteArray(200)))
        assertNull(run(BinaryDexInspector, BinBuf(200).str(0, "dex\nabc").a))
    }

    @Test fun dexTruncation() = assertTruncationSafe(BinaryDexInspector, dex())

    @Test fun dexHostileLengths() {
        val b = dex()
        b.fill(0xFF.toByte(), 32, 36)
        b.fill(0xFF.toByte(), 56, 112)
        assertBounded(BinaryDexInspector, b)
        val r = run(BinaryDexInspector, b)!!
        assertEquals("4294967295", r.f("Strings"))
        assertNull(r.f("Checksum valid"))
    }

    // ------------------------------------------------------------------ WebAssembly

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

    @Test fun wasmFacts() {
        val r = run(BinaryWasmInspector, wasm())!!
        assertEquals("wasm", r.formatId)
        assertEquals("1", r.f("Version"))
        assertEquals("7", r.f("Sections", "Sections"))
        assertEquals("1", r.f("Types"))
        assertEquals("1", r.f("Imports"))
        assertEquals("1", r.f("Imported functions"))
        assertEquals("1", r.f("Defined functions"))
        assertEquals("2", r.f("Total functions"))
        assertEquals("1", r.f("Memories"))
        assertEquals("2", r.f("Exports"))
        assertEquals("1", r.f("Code bodies"))
        assertEquals("env.log (func)", r.f("Import"))
        assertEquals(listOf("memory (memory 0)", "run (function 1)"), r.all("Export"))
        assertTrue(r.f("Custom section: name") != null)
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun wasmComponentAndUnknownVersion() {
        val c = run(BinaryWasmInspector, byteArrayOf(0, 0x61, 0x73, 0x6D, 0x0D, 0, 1, 0))!!
        assertEquals("WebAssembly component", c.title)
        val u = run(BinaryWasmInspector, byteArrayOf(0, 0x61, 0x73, 0x6D, 9, 0, 0, 0))!!
        assertTrue(u.warnings.isNotEmpty())
    }

    @Test fun wasmRejectsOthers() {
        assertNull(run(BinaryWasmInspector, ByteArray(20)))
    }

    @Test fun wasmTruncation() = assertTruncationSafe(BinaryWasmInspector, wasm())

    @Test fun wasmHostileLengths() {
        val head = byteArrayOf(0, 0x61, 0x73, 0x6D, 1, 0, 0, 0)
        val huge = head + byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F, 1, 2, 3)
        assertBounded(BinaryWasmInspector, huge)
        assertTrue(run(BinaryWasmInspector, huge)!!.warnings.isNotEmpty())
        val imp = head + wsec(2, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F) + wstr("a") + wstr("b") + byteArrayOf(0, 0))
        assertBounded(BinaryWasmInspector, imp)
        val badName = head + wsec(7, byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F, 1, 2))
        assertBounded(BinaryWasmInspector, badName)
        val overlong = head + byteArrayOf(1, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())
        assertBounded(BinaryWasmInspector, overlong)
    }

    // ------------------------------------------------------------------ SQLite

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
        val p = BinBuf(pageSize)
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
        val b = BinBuf(0)
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
        val p1 = BinBuf(1024)
        p1.u8(100, 0x05).be16(100 + 3, 1).be32(100 + 8, 3)
        val cell = BinBuf(5).be32(0, 2).u8(4, 1).a
        p1.put(1024 - 5, cell).be16(100 + 12, 1024 - 5)
        System.arraycopy(p1.a, 0, db, 0, 1024)
        System.arraycopy(leafPage(1024, 0, listOf(leafCell(1, record("table", "users", "users", 2, usersSql)))), 0, db, 1024, 1024)
        System.arraycopy(leafPage(1024, 0, listOf(leafCell(2, record("table", "posts", "posts", 3, postsSql)))), 0, db, 2048, 1024)
        fillHeader(db, 3)
        return db
    }

    @Test fun sqliteFacts() {
        val r = run(BinarySqliteInspector, sqliteLeaf())!!
        assertEquals("sqlite", r.formatId)
        assertEquals("1024 bytes", r.f("Page size"))
        assertEquals("rollback journal (write 1, read 1)", r.f("Journal mode (file format)"))
        assertEquals("4", r.f("Page count"))
        assertEquals("2", r.f("Freelist pages"))
        assertEquals("5", r.f("Schema cookie (schema version)"))
        assertEquals("UTF-8", r.f("Text encoding"))
        assertEquals("42", r.f("User version"))
        assertEquals("0x47504b47 (GeoPackage)", r.f("Application ID"))
        assertEquals("3.45.0", r.f("Last written by SQLite"))
        assertEquals("2", r.f("Tables", "Schema"))
        assertEquals("1", r.f("Indexes"))
        assertEquals(listOf("users", "posts"), r.all("Table"))
        assertTrue(r.text!!.contains(usersSql))
        assertTrue(r.text!!.contains(idxSql))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun sqliteInteriorSchemaTree() {
        val r = run(BinarySqliteInspector, sqliteInterior())!!
        assertEquals(listOf("users", "posts"), r.all("Table"))
        assertEquals("3", r.f("Page count"))
    }

    @Test fun sqliteDerivesPageCountWhenHeaderStale() {
        val db = sqliteLeaf()
        db[92] = 0; db[93] = 0; db[94] = 0; db[95] = 1
        val r = run(BinarySqliteInspector, db)!!
        assertTrue(r.f("Page count")!!.startsWith("4 (derived"))
    }

    @Test fun sqliteRejectsOthers() {
        assertNull(run(BinarySqliteInspector, ByteArray(200)))
        assertNull(run(BinarySqliteInspector, "SQLite format 4\u0000".toByteArray()))
    }

    @Test fun sqliteTruncation() = assertTruncationSafe(BinarySqliteInspector, sqliteLeaf())

    @Test fun sqliteHostileLengths() {
        val a = sqliteLeaf()
        a[103] = 0xFF.toByte(); a[104] = 0xFF.toByte()                // cell count 65535
        assertBounded(BinarySqliteInspector, a)
        val b = sqliteInterior()
        b.fill(0xFF.toByte(), 108, 112)                                // right-most child 0xFFFFFFFF
        b.fill(0xFF.toByte(), 1024 - 5, 1024 - 1)                      // left child 0xFFFFFFFF
        assertBounded(BinarySqliteInspector, b)
        val c = sqliteInterior()
        c[1024 - 5 + 3] = 1                                            // left child -> page 1 (cycle)
        c[1024 - 5] = 0; c[1024 - 4] = 0; c[1024 - 3] = 0
        assertBounded(BinarySqliteInspector, c)
        val d = sqliteLeaf()
        d[16] = 0; d[17] = 0                                           // page size 0
        assertBounded(BinarySqliteInspector, d)
        val e = sqliteLeaf()
        e[1024 - 1] = 0xFF.toByte(); e.fill(0xFF.toByte(), 1024 - 20, 1024 - 10) // garbage in a cell
        assertBounded(BinarySqliteInspector, e)
        val f = sqliteLeaf()
        f.fill(0xFF.toByte(), 108, 114)                                // wild cell pointers
        assertBounded(BinarySqliteInspector, f)
    }

    // ------------------------------------------------------------------ pyc

    private fun pyc311(): ByteArray {
        val b = BinBuf(17)
        b.put(0, byteArrayOf(0xA7.toByte(), 0x0D, 0x0D, 0x0A)).le32(4, 0).le32(8, 1600000000).le32(12, 1234).u8(16, 0xE3)
        return b.a
    }

    @Test fun pycTimestampBased() {
        val r = run(BinaryPycInspector, pyc311())!!
        assertEquals("pyc", r.formatId)
        assertEquals("3495", r.f("Magic number"))
        assertEquals("CPython 3.11", r.f("Python version"))
        assertEquals("timestamp", r.f("Invalidation mode"))
        assertEquals("2020-09-13T12:26:40Z", r.f("Source modified"))
        assertEquals("1234 bytes", r.f("Source size"))
        assertTrue(r.f("Marshalled object")!!.startsWith("code object"))
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test fun pycHashBased() {
        val b = BinBuf(17)
        b.put(0, byteArrayOf(0x42, 0x0D, 0x0D, 0x0A)).le32(4, 3).put(8, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)).u8(16, 0x63)
        val r = run(BinaryPycInspector, b.a)!!
        assertEquals("CPython 3.7", r.f("Python version"))
        assertEquals("checked hash", r.f("Invalidation mode"))
        assertEquals("0102030405060708", r.f("Source hash (SipHash)"))
    }

    @Test fun pycOlderLayouts() {
        val b36 = BinBuf(13).put(0, byteArrayOf(0x33, 0x0D, 0x0D, 0x0A)).le32(4, 1600000000).le32(8, 99).u8(12, 0xE3)
        val r36 = run(BinaryPycInspector, b36.a)!!
        assertEquals("CPython 3.6", r36.f("Python version"))
        assertEquals("99 bytes", r36.f("Source size"))
        val b27 = BinBuf(9).put(0, byteArrayOf(0x03, 0xF3.toByte(), 0x0D, 0x0A)).le32(4, 1600000000).u8(8, 0x63)
        val r27 = run(BinaryPycInspector, b27.a)!!
        assertEquals("CPython 2.7", r27.f("Python version"))
        assertEquals("2020-09-13T12:26:40Z", r27.f("Source modified"))
    }

    @Test fun pycRejectsOthers() {
        assertNull(run(BinaryPycInspector, ByteArray(30)))
        assertNull(run(BinaryPycInspector, byteArrayOf(1, 2, 0x0D, 0x0A, 0, 0, 0, 0)))
    }

    @Test fun pycTruncation() = assertTruncationSafe(BinaryPycInspector, pyc311())

    @Test fun pycHostileFields() {
        val b = pyc311()
        b.fill(0xFF.toByte(), 4, 16)
        assertBounded(BinaryPycInspector, b)
        assertTrue(run(BinaryPycInspector, b)!!.warnings.isNotEmpty())
    }

    // ------------------------------------------------------------------ registry

    @Test fun registryListsEverything() {
        val ids = BinaryInspectors.all.flatMap { it.formatIds }.toSet()
        assertTrue(ids.containsAll(setOf("elf", "exe", "macho", "class", "dex", "wasm", "sqlite", "pyc")))
        assertEquals(8, BinaryInspectors.all.size)
        assertFalse(BinaryInspectors.all.any { it.formatIds.isEmpty() })
    }

    @Test fun randomGarbageNeverThrows() {
        val rnd = java.util.Random(1234)
        for (round in 0 until 200) {
            val b = ByteArray(rnd.nextInt(600)); rnd.nextBytes(b)
            for (i in BinaryInspectors.all) i.inspect(ByteArraySource(b), "f")
        }
        // garbage behind each real magic
        val magics = listOf(elf64(), pe(), machoThin64(), machoFat(), javaClass8(), dex(), wasm(), sqliteLeaf(), pyc311())
        for (m in magics) for (round in 0 until 100) {
            val b = m.copyOf(); val k = rnd.nextInt(8) + 1
            repeat(k) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            for (i in BinaryInspectors.all) i.inspect(ByteArraySource(b), "f")
        }
    }
}
