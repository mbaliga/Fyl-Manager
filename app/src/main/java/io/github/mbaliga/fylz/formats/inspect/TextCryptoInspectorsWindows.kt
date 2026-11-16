package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.util.zip.CRC32

/** Windows shell link (.lnk), per [MS-SHLLINK]. */
object LnkInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("lnk")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private val clsid = intArrayOf(0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46)

    private fun cstr(b: ByteArray, off: Int, unicode: Boolean, limit: Int): String? {
        if (off < 0 || off >= limit) return null
        if (unicode) {
            var e = off
            while (e + 1 < limit && !(b[e].toInt() == 0 && b[e + 1].toInt() == 0) && e - off < 2048) e += 2
            return String(b, off, e - off, Charsets.UTF_16LE)
        }
        var e = off
        while (e < limit && b[e].toInt() != 0 && e - off < 1024) e++
        return String(b, off, e - off, Charsets.ISO_8859_1)
    }

    private fun run(source: ByteSource): InspectionResult? {
        val b = TC.readUpTo(source, 256 * 1024)
        if (b.size < 76 || Bytes.u32le(b, 0) != 0x4CL) return null
        for (i in clsid.indices) if ((b[4 + i].toInt() and 0xFF) != clsid[i]) return null
        val flags = Bytes.u32le(b, 0x14)
        val attrs = Bytes.u32le(b, 0x18)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        Bytes.u64le(b, 0x1C).let { TC.fileTime(it) }?.let { facts.add(Fact("Target created", it, "Target")) }
        Bytes.u64le(b, 0x24).let { TC.fileTime(it) }?.let { facts.add(Fact("Target accessed", it, "Target")) }
        Bytes.u64le(b, 0x2C).let { TC.fileTime(it) }?.let { facts.add(Fact("Target modified", it, "Target")) }
        facts.add(Fact("Target size", Bytes.humanSize(Bytes.u32le(b, 0x34)), "Target"))
        val attrNames = listOf(0 to "read-only", 1 to "hidden", 2 to "system", 4 to "directory", 5 to "archive")
            .filter { (attrs and (1L shl it.first)) != 0L }.map { it.second }
        if (attrNames.isNotEmpty()) facts.add(Fact("Target attributes", attrNames.joinToString(", "), "Target"))
        val show = when (Bytes.u32le(b, 0x3C).toInt()) { 1 -> "normal window"; 3 -> "maximized"; 7 -> "minimized (not activated)"; else -> null }
        if (show != null) facts.add(Fact("Window", show, "Launch"))
        val hot = Bytes.u16le(b, 0x40)
        if (hot != 0) facts.add(Fact("Hotkey code", "0x" + Integer.toHexString(hot), "Launch"))

        var pos = 76
        if ((flags and 1L) != 0L) {
            val sz = Bytes.u16le(b, pos)
            facts.add(Fact("Shell item list", "present ($sz bytes)", "Target"))
            pos += 2 + sz
        }
        var targetPath: String? = null
        if ((flags and 2L) != 0L && pos + 28 <= b.size) {
            val li = pos
            val liSize = Bytes.u32le(b, li)
            val hdr = Bytes.u32le(b, li + 4)
            val liFlags = Bytes.u32le(b, li + 8)
            if (liSize in 28..(b.size - li).toLong() && hdr >= 28) {
                val lim = li + liSize.toInt()
                var local: String? = null
                var suffix: String? = null
                if ((liFlags and 1L) != 0L) {
                    val volOff = Bytes.u32le(b, li + 12)
                    val baseOff = Bytes.u32le(b, li + 16)
                    val baseOffU = if (hdr >= 0x24) Bytes.u32le(b, li + 28) else 0L
                    local = if (baseOffU != 0L && baseOffU < liSize) cstr(b, li + baseOffU.toInt(), true, lim) else if (baseOff < liSize) cstr(b, li + baseOff.toInt(), false, lim) else null
                    if (volOff in 1 until liSize - 12) {
                        val v = li + volOff.toInt()
                        val dt = Bytes.u32le(b, v + 4).toInt()
                        val dtn = when (dt) { 1 -> "no root directory"; 2 -> "removable"; 3 -> "fixed disk"; 4 -> "network"; 5 -> "CD-ROM"; 6 -> "RAM disk"; else -> null }
                        if (dtn != null) facts.add(Fact("Volume type", dtn, "Target"))
                        facts.add(Fact("Volume serial", "%08X".format(Bytes.u32le(b, v + 8)), "Target"))
                    }
                }
                var network: String? = null
                if ((liFlags and 2L) != 0L) {
                    val nOff = Bytes.u32le(b, li + 20)
                    if (nOff in 1 until liSize - 12) {
                        val n = li + nOff.toInt()
                        val netNameOff = Bytes.u32le(b, n + 8)
                        if (netNameOff < liSize - nOff) network = cstr(b, n + netNameOff.toInt(), false, lim)
                    }
                }
                val sufOff = Bytes.u32le(b, li + 24)
                val sufOffU = if (hdr >= 0x24) Bytes.u32le(b, li + 32) else 0L
                if (sufOffU != 0L && sufOffU < liSize) suffix = cstr(b, li + sufOffU.toInt(), true, lim)
                else if (sufOff in 1 until liSize) suffix = cstr(b, li + sufOff.toInt(), false, lim)
                targetPath = when {
                    !local.isNullOrEmpty() -> local + (suffix ?: "")
                    !network.isNullOrEmpty() -> network + (if (!suffix.isNullOrEmpty()) "\\" + suffix else "")
                    else -> null
                }
                if (network != null) facts.add(Fact("Network share", network, "Target"))
            } else warnings.add("LinkInfo structure has an invalid size")
            pos = li + maxOf(0L, minOf(liSize, (b.size - li).toLong())).toInt()
        }
        if (targetPath != null) facts.add(0, Fact("Target path", targetPath, "Target"))
        val unicode = (flags and 0x80L) != 0L
        val strings = listOf(2 to "Description", 3 to "Relative path", 4 to "Working directory", 5 to "Arguments", 6 to "Icon file")
        var title: String? = null
        for ((bit, label) in strings) {
            if ((flags and (1L shl bit)) == 0L) continue
            if (pos + 2 > b.size) { warnings.add("Truncated before the $label string"); break }
            val n = Bytes.u16le(b, pos)
            val bytes = if (unicode) n * 2 else n
            if (pos + 2 + bytes > b.size) { warnings.add("$label string runs past the read window"); break }
            val s = String(b, pos + 2, bytes, if (unicode) Charsets.UTF_16LE else Charsets.ISO_8859_1)
            pos += 2 + bytes
            if (s.isNotEmpty()) {
                facts.add(Fact(label, TC.cap(s, 500), if (label == "Description" || label == "Icon file") "Shortcut" else "Launch"))
                if (label == "Description") title = s
            }
        }
        if (targetPath == null && (flags and 1L) != 0L) warnings.add("Target is stored only as a shell item list, which is not decoded here")
        val summary = targetPath ?: facts.firstOrNull { it.label == "Relative path" }?.value
        return InspectionResult("lnk", "Windows shortcut", summary, facts, warnings)
    }
}

/** Windows registry hive ("regf") header. */
object RegistryHiveInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("hiv")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source, fileName) }

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val b = TC.readUpTo(source, 8192)
        if (b.size < 512 || !Bytes.startsWith(b, "regf")) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        val p = Bytes.u32le(b, 4); val s = Bytes.u32le(b, 8)
        facts.add(Fact("Version", "${Bytes.u32le(b, 0x14)}.${Bytes.u32le(b, 0x18)}", "Header"))
        TC.fileTime(Bytes.u64le(b, 0x0C))?.let { facts.add(Fact("Last written", it, "Header")) }
        val type = Bytes.u32le(b, 0x1C)
        facts.add(Fact("File type", if (type == 0L) "primary hive" else "type $type", "Header"))
        facts.add(Fact("Root cell offset", "0x" + java.lang.Long.toHexString(Bytes.u32le(b, 0x24)), "Header"))
        facts.add(Fact("Hive bins size", Bytes.humanSize(Bytes.u32le(b, 0x28)), "Header"))
        var e = 0x30
        while (e + 1 < 0x30 + 64 && !(b[e].toInt() == 0 && b[e + 1].toInt() == 0)) e += 2
        val name = String(b, 0x30, e - 0x30, Charsets.UTF_16LE)
        if (name.isNotEmpty()) facts.add(Fact("Hive name", name, "Header"))
        facts.add(Fact("Sequence numbers", "$p / $s", "Consistency"))
        if (p != s) warnings.add("Primary and secondary sequence numbers differ: the hive was not cleanly written and may need its transaction logs replayed")
        var x = 0L
        for (i in 0 until 127) x = x xor Bytes.u32le(b, i * 4)
        val stored = Bytes.u32le(b, 0x1FC)
        if (x == stored) facts.add(Fact("Header checksum", "valid", "Consistency"))
        else warnings.add("Header checksum mismatch (stored 0x%08X, computed 0x%08X)".format(stored, x))
        if (b.size >= 4100 && !Bytes.startsWith(b, "hbin", 4096)) warnings.add("First hive bin signature not found at offset 4096")
        val short = name.substringAfterLast('\\').ifEmpty { fileName ?: "" }
        return InspectionResult("hiv", "Windows registry hive", short.ifEmpty { null }, facts, warnings)
    }
}

/** Windows XML event log (.evtx) file header. */
object EvtxInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("evtx")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private fun run(source: ByteSource): InspectionResult? {
        val b = TC.readUpTo(source, 4096)
        if (b.size < 128 || !Bytes.startsWith(b, "ElfFile\u0000")) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts.add(Fact("Version", "${Bytes.u16le(b, 38)}.${Bytes.u16le(b, 36)}", "Header"))
        val chunks = Bytes.u16le(b, 42)
        facts.add(Fact("Chunk count", chunks.toString(), "Header"))
        facts.add(Fact("First chunk", Bytes.u64le(b, 8).toString(), "Header"))
        facts.add(Fact("Last chunk", Bytes.u64le(b, 16).toString(), "Header"))
        facts.add(Fact("Next record ID", java.lang.Long.toUnsignedString(Bytes.u64le(b, 24)), "Header"))
        val fl = Bytes.u32le(b, 120)
        val fln = listOfNotNull(if ((fl and 1L) != 0L) "dirty" else null, if ((fl and 2L) != 0L) "full" else null)
        facts.add(Fact("Flags", if (fln.isEmpty()) "none" else fln.joinToString(", "), "Header"))
        val crc = CRC32(); crc.update(b, 0, 120)
        if (crc.value == Bytes.u32le(b, 124)) facts.add(Fact("Header checksum", "valid", "Header"))
        else warnings.add("Header CRC-32 mismatch")
        val hb = Bytes.u16le(b, 40)
        val expected = hb.toLong() + chunks.toLong() * 65536L
        if (source.size < expected) warnings.add("File is smaller than the header implies ($expected bytes expected)")
        if ((fl and 1L) != 0L) warnings.add("The log was marked dirty (not cleanly closed)")
        return InspectionResult("evtx", "Windows event log", "$chunks chunk${if (chunks == 1) "" else "s"}", facts, warnings)
    }
}
