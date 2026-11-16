package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Java .class files: version, constant pool, access flags, class names. */
object BinaryClassInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("class")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = binSafe { run(source) }

    fun javaVersion(major: Int): String? = when {
        major < 45 || major > 90 -> null
        major <= 48 -> "1.${major - 44}"
        else -> "${major - 44}"
    }

    private fun run(source: ByteSource): InspectionResult? {
        val rd = BinReader(source)
        val b = rd.read(0, BinReader.MAX_CHUNK.toLong())
        if (b.size < 10 || Bytes.u32be(b, 0) != 0xCAFEBABEL) return null
        val minor = Bytes.u16be(b, 4)
        val major = Bytes.u16be(b, 6)
        if (major < 45) return null // fat Mach-O headers share this magic; their next field is a tiny count
        val count = Bytes.u16be(b, 8)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val jv = javaVersion(major)
        facts += Fact("Class file version", "$major.$minor", "Version")
        facts += Fact("Java version", if (jv != null) "Java $jv" + (if (minor == 0xFFFF) " (preview features)" else "") else "unknown (major $major)", "Version")
        facts += Fact("Constant pool entries", maxOf(count - 1, 0).toString(), "Constant pool")

        val utfOff = IntArray(count) { -1 }
        val utfLen = IntArray(count)
        val classNameIdx = IntArray(count)
        var p = 10L
        var i = 1
        var poolOk = true
        var unknownTag = -1
        while (i < count) {
            if (p >= b.size) { poolOk = false; break }
            val pi = p.toInt()
            val tag = Bytes.u8(b, pi)
            when (tag) {
                1 -> {
                    if (pi + 3 > b.size) { poolOk = false; break }
                    val len = Bytes.u16be(b, pi + 1)
                    utfOff[i] = pi + 3; utfLen[i] = len
                    p += 3L + len
                }
                3, 4 -> p += 5
                5, 6 -> { p += 9; i++ }
                7 -> { classNameIdx[i] = Bytes.u16be(b, pi + 1); p += 3 }
                8, 16, 19, 20 -> p += 3
                9, 10, 11, 12, 17, 18 -> p += 5
                15 -> p += 4
                else -> { unknownTag = tag; poolOk = false }
            }
            if (unknownTag >= 0) break
            i++
        }
        if (unknownTag >= 0) warnings += "Unknown constant pool tag $unknownTag; the rest of the file was not parsed"
        else if (!poolOk || p > b.size) {
            warnings += if (b.size.toLong() >= BinReader.MAX_CHUNK) "Constant pool is larger than the inspected window" else "Constant pool is truncated"
            poolOk = false
        }

        fun utf(idx: Int): String? {
            if (idx <= 0 || idx >= count || utfOff[idx] < 0) return null
            val o = utfOff[idx]; val l = utfLen[idx]
            if (o + l > b.size) return null
            return BinFmt.clean(String(b, o, minOf(l, 1024), Charsets.UTF_8))
        }
        fun cls(idx: Int): String? {
            if (idx <= 0 || idx >= count) return null
            return utf(classNameIdx[idx])?.replace('/', '.')
        }

        var kindWord = "class"
        var thisName: String? = null
        if (poolOk && p + 8 <= b.size) {
            val q = p.toInt()
            val acc = Bytes.u16be(b, q)
            val thisIdx = Bytes.u16be(b, q + 2)
            val superIdx = Bytes.u16be(b, q + 4)
            val nIf = Bytes.u16be(b, q + 6)
            val flags = buildList {
                if (acc and 0x0001 != 0) add("public")
                if (acc and 0x0010 != 0) add("final")
                if (acc and 0x0020 != 0) add("super")
                if (acc and 0x0200 != 0) add("interface")
                if (acc and 0x0400 != 0) add("abstract")
                if (acc and 0x1000 != 0) add("synthetic")
                if (acc and 0x2000 != 0) add("annotation")
                if (acc and 0x4000 != 0) add("enum")
                if (acc and 0x8000 != 0) add("module")
            }
            kindWord = when {
                acc and 0x8000 != 0 -> "module"
                acc and 0x2000 != 0 -> "annotation"
                acc and 0x0200 != 0 -> "interface"
                acc and 0x4000 != 0 -> "enum"
                else -> "class"
            }
            facts += Fact("Access flags", "0x%04x".format(acc) + if (flags.isEmpty()) "" else " (${flags.joinToString(" ")})", "Class")
            facts += Fact("Kind", kindWord, "Class")
            thisName = cls(thisIdx)
            thisName?.let { facts += Fact("This class", it, "Class") }
            if (superIdx == 0) facts += Fact("Super class", "none (java.lang.Object or module-info)", "Class")
            else cls(superIdx)?.let { facts += Fact("Super class", it, "Class") }
            facts += Fact("Interfaces", nIf.toString(), "Class")
            val names = ArrayList<String>()
            for (k in 0 until minOf(nIf, 64)) if (q + 8 + k * 2 + 2 <= b.size) cls(Bytes.u16be(b, q + 8 + k * 2))?.let { names += it }
            if (names.isNotEmpty()) facts += Fact("Implements / extends", BinFmt.list(names, 24), "Class")

            // members and class attributes, skipped by length
            var m = q.toLong() + 8 + nIf * 2L
            fun members(label: String): Boolean {
                if (m + 2 > b.size) return false
                val n = Bytes.u16be(b, m.toInt()); m += 2
                for (x in 0 until n) {
                    if (m + 8 > b.size) return false
                    val na = Bytes.u16be(b, m.toInt() + 6); m += 8
                    for (y in 0 until na) {
                        if (m + 6 > b.size) return false
                        m += 6L + Bytes.u32be(b, m.toInt() + 2)
                    }
                }
                if (m > b.size) return false
                facts += Fact(label, n.toString(), "Class")
                return true
            }
            if (members("Fields") && members("Methods") && m + 2 <= b.size) {
                val na = Bytes.u16be(b, m.toInt()); m += 2
                var shown = false
                for (y in 0 until na) {
                    if (m + 6 > b.size) break
                    val len = Bytes.u32be(b, m.toInt() + 2)
                    if (utf(Bytes.u16be(b, m.toInt())) == "SourceFile" && len == 2L && m + 8 <= b.size) {
                        utf(Bytes.u16be(b, m.toInt() + 6))?.let { facts += Fact("Source file", it, "Class"); shown = true }
                    }
                    m += 6 + len
                }
                facts += Fact("Class attributes", na.toString(), "Class")
            } else warnings += "Fields/methods tables are truncated or exceed the inspected window"
        }

        val title = (thisName?.let { "$it - " } ?: "") + "Java $kindWord file" + (jv?.let { " (Java $it)" } ?: "")
        return InspectionResult("class", title, "Java $kindWord, class file version $major.$minor" + (jv?.let { " (Java $it)" } ?: ""), facts, warnings)
    }
}

/** Android Dalvik Executable (.dex). */
object BinaryDexInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("dex")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = binSafe { run(source) }

    private fun run(source: ByteSource): InspectionResult? {
        val rd = BinReader(source)
        val h = rd.read(0, 112)
        if (h.size < 8 || !Bytes.startsWith(h, "dex\n") || h[7].toInt() != 0) return null
        val ver = Bytes.ascii(h, 4, 3)
        if (!ver.all { it in '0'..'9' }) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts += Fact("Version", ver, "Header")
        if (h.size < 112) {
            warnings += "DEX header is truncated (${h.size} of 112 bytes)"
            return InspectionResult("dex", "Dalvik executable (version $ver)", "DEX version $ver", facts, warnings)
        }
        val checksum = Bytes.u32le(h, 8)
        val fileSize = Bytes.u32le(h, 32)
        val headerSize = Bytes.u32le(h, 36)
        val endian = Bytes.u32le(h, 40)
        facts += Fact("Checksum (Adler-32)", "0x%08x".format(checksum), "Header")
        facts += Fact("Signature (SHA-1)", BinFmt.hex(h.copyOfRange(12, 32)), "Header")
        facts += Fact("Declared file size", "$fileSize bytes", "Header")
        facts += Fact("Header size", "$headerSize bytes", "Header")
        facts += Fact("Endianness tag", when (endian) {
            0x12345678L -> "little-endian (standard)"
            0x78563412L -> "byte-swapped"
            else -> "invalid (${BinFmt.hex(endian)})"
        }, "Header")
        if (headerSize != 112L) warnings += "Header size is $headerSize, expected 112"
        if (endian != 0x12345678L) warnings += "Unexpected endian tag ${BinFmt.hex(endian)}"
        if (fileSize != rd.size) warnings += "Declared file size ($fileSize) differs from actual size (${rd.size})"
        facts += Fact("Strings", Bytes.u32le(h, 56).toString(), "Counts")
        facts += Fact("Types", Bytes.u32le(h, 64).toString(), "Counts")
        facts += Fact("Prototypes", Bytes.u32le(h, 72).toString(), "Counts")
        facts += Fact("Fields", Bytes.u32le(h, 80).toString(), "Counts")
        facts += Fact("Methods", Bytes.u32le(h, 88).toString(), "Counts")
        facts += Fact("Classes", Bytes.u32le(h, 96).toString(), "Counts")
        facts += Fact("Data section", "${Bytes.u32le(h, 104)} bytes at offset ${BinFmt.hex(Bytes.u32le(h, 108))}", "Layout")
        facts += Fact("Map offset", BinFmt.hex(Bytes.u32le(h, 52)), "Layout")
        if (Bytes.u32le(h, 44) != 0L) facts += Fact("Link section", "${Bytes.u32le(h, 44)} bytes at ${BinFmt.hex(Bytes.u32le(h, 48))}", "Layout")

        // verify checksum and signature when the whole file fits the read budget
        if (fileSize == rd.size && fileSize in 32..(7L * 1024 * 1024)) {
            val adler = java.util.zip.Adler32()
            val sha = java.security.MessageDigest.getInstance("SHA-1")
            var pos = 12L
            while (pos < fileSize) {
                val chunk = rd.read(pos, 1L shl 20)
                if (chunk.isEmpty()) break
                adler.update(chunk, 0, chunk.size)
                val from = if (pos < 32) (32 - pos).toInt().coerceAtMost(chunk.size) else 0
                sha.update(chunk, from, chunk.size - from)
                pos += chunk.size
            }
            if (pos >= fileSize) {
                facts += Fact("Checksum valid", if (adler.value == checksum) "yes" else "no", "Header")
                if (adler.value != checksum) warnings += "Adler-32 checksum does not match the file contents"
                val sigOk = sha.digest().contentEquals(h.copyOfRange(12, 32))
                facts += Fact("Signature valid", if (sigOk) "yes" else "no", "Header")
                if (!sigOk) warnings += "SHA-1 signature does not match the file contents"
            }
        }
        val title = "Dalvik executable (DEX version $ver)"
        return InspectionResult("dex", title, "$title, ${Bytes.u32le(h, 96)} classes, ${Bytes.u32le(h, 88)} methods", facts, warnings)
    }
}
