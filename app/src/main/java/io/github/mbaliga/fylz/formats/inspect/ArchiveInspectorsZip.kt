package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** ZIP (and flavours jar/apk/whl/nupkg): EOCD (incl. zip64), central directory, manifest hints. */
internal object ArchiveZipInspector : ArcBase() {
    override val formatIds: Set<String> = setOf("zip", "jar", "apk", "whl", "nupkg")

    const val MAX_ENTRIES_LISTED = 20
    private const val MAX_CD_BYTES = 4 * 1024 * 1024
    private const val MAX_ENTRIES = 200_000

    fun methodName(m: Int): String = when (m) {
        0 -> "Stored"; 1 -> "Shrunk"; 6 -> "Imploded"; 8 -> "Deflated"; 9 -> "Deflate64"
        12 -> "BZIP2"; 14 -> "LZMA"; 93 -> "Zstandard"; 95 -> "XZ"; 98 -> "PPMd"; 99 -> "AES-encrypted"
        else -> "method $m"
    }

    private class Ent(val name: String, val method: Int, val flags: Int, val csize: Long, val usize: Long, val localOff: Long, val dos: String?)

    override fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val size = source.size
        if (size < 4) return null
        val head = ArcUtil.readAt(source, 0, 4)
        val tailLen = minOf(size, 65535L + 22 + 20 + 64).toInt()
        val tailStart = size - tailLen
        val tail = ArcUtil.readAt(source, tailStart, tailLen)

        var ei = -1
        var exact = false
        var i = tail.size - 22
        while (i >= 0) {
            if (ArcUtil.eq(tail, i, "PK\u0005\u0006")) {
                val cl = Bytes.u16le(tail, i + 20)
                if (i + 22 + cl == tail.size) { ei = i; exact = true; break }
                if (ei < 0 && i + 22 + cl <= tail.size) ei = i
            }
            i--
        }
        val ext = ArcUtil.ext(fileName)
        val fid = if (ext != null && ext in formatIds) ext else "zip"
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()

        if (ei < 0) {
            if (!ArcUtil.eq(head, 0, "PK\u0003\u0004")) return null
            val h = ArcUtil.readAt(source, 0, 30 + 1024)
            val nl = Bytes.u16le(h, 26)
            facts += Fact("First entry", ArcUtil.utf8(h, 30, nl), "Entries")
            facts += Fact("Method", ArchiveZipInspector.methodName(Bytes.u16le(h, 8)), "Entries")
            warnings += "End of central directory not found: file is truncated or damaged"
            return InspectionResult(fid, "ZIP archive", "ZIP archive (damaged: no central directory)", facts, warnings)
        }

        val eocdAbs = tailStart + ei
        var entriesTotal = Bytes.u16le(tail, ei + 10).toLong()
        var cdSize = Bytes.u32le(tail, ei + 12)
        var cdOff = Bytes.u32le(tail, ei + 16)
        val diskNo = Bytes.u16le(tail, ei + 4)
        val cdDisk = Bytes.u16le(tail, ei + 6)
        val cl = Bytes.u16le(tail, ei + 20)
        var zip64 = false
        var base = eocdAbs
        if (ei >= 20 && ArcUtil.eq(tail, ei - 20, "PK\u0006\u0007")) {
            val z64 = Bytes.u64le(tail, ei - 20 + 8)
            if (z64 in 0..(size - 56)) {
                val rec = ArcUtil.readAt(source, z64, 56)
                if (ArcUtil.eq(rec, 0, "PK\u0006\u0006") && rec.size >= 56) {
                    zip64 = true
                    entriesTotal = Bytes.u64le(rec, 32)
                    cdSize = Bytes.u64le(rec, 40)
                    cdOff = Bytes.u64le(rec, 48)
                    base = z64
                    facts += Fact("Zip64 version made by", Bytes.u16le(rec, 12).toString(), "Archive")
                }
            }
        }
        if (!zip64 && (entriesTotal == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL))
            warnings += "Zip64 values expected but no valid zip64 end-of-central-directory record found"
        if (!exact) warnings += "Data follows the end-of-central-directory record or comment length is inconsistent"

        facts += Fact("Zip64", if (zip64) "yes" else "no", "Archive")
        facts += Fact("Declared entry count", entriesTotal.toString(), "Archive")
        facts += Fact("Central directory size", ArcUtil.size(cdSize), "Archive")
        facts += Fact("Central directory offset", cdOff.toString(), "Archive")
        if (diskNo != 0 || cdDisk != 0) facts += Fact("Multi-disk", "disk $diskNo, central directory on disk $cdDisk", "Archive")

        // Locate central directory (allow for prepended data such as a self-extractor stub).
        var shift = 0L
        val cdOkAtOff = cdOff in 0..(size - 4) && ArcUtil.eq(ArcUtil.readAt(source, cdOff, 4), 0, "PK\u0001\u0002")
        if (!cdOkAtOff && cdSize in 1..base) {
            val alt = base - cdSize
            if (ArcUtil.eq(ArcUtil.readAt(source, alt, 4), 0, "PK\u0001\u0002")) {
                shift = alt - cdOff
                cdOff = alt
                facts += Fact("Prepended data", ArcUtil.size(shift), "Archive")
            }
        }

        val readLen = ArcUtil.clampInt(minOf(cdSize, maxOf(0L, size - cdOff)), MAX_CD_BYTES)
        val cd = if (cdOff >= 0) ArcUtil.readAt(source, cdOff, readLen) else ByteArray(0)

        var p = 0
        var count = 0L; var dirs = 0L; var enc = 0L
        var totalC = 0L; var totalU = 0L
        val listed = ArrayList<Ent>()
        var manifest: Ent? = null; var mimetype: Ent? = null
        var hasAndroid = false; var hasDex = false; var hasContentTypes = false
        var hasClass = false; var wheelDir: String? = null; var nuspec: String? = null
        var hasContainerXml = false
        var newest: String? = null
        val methods = sortedMapOf<Int, Int>()

        while (count < MAX_ENTRIES && p + 46 <= cd.size && ArcUtil.eq(cd, p, "PK\u0001\u0002")) {
            val flags = Bytes.u16le(cd, p + 8)
            val method = Bytes.u16le(cd, p + 10)
            val dos = ArcUtil.dosDateTime(Bytes.u16le(cd, p + 14), Bytes.u16le(cd, p + 12))
            var cs = Bytes.u32le(cd, p + 20)
            var us = Bytes.u32le(cd, p + 24)
            val nl = Bytes.u16le(cd, p + 28); val xl = Bytes.u16le(cd, p + 30); val cm = Bytes.u16le(cd, p + 32)
            var lo = Bytes.u32le(cd, p + 42)
            if (p + 46 + nl > cd.size) break
            val name = if (flags and 0x800 != 0) ArcUtil.utf8(cd, p + 46, nl) else cp437(cd, p + 46, nl)
            // zip64 extra
            var q = p + 46 + nl
            val xEnd = minOf(cd.size, q + xl)
            while (q + 4 <= xEnd) {
                val id = Bytes.u16le(cd, q); val sz = Bytes.u16le(cd, q + 2)
                if (id == 1) {
                    var r = q + 4
                    if (us == 0xFFFFFFFFL && r + 8 <= xEnd) { us = Bytes.u64le(cd, r); r += 8 }
                    if (cs == 0xFFFFFFFFL && r + 8 <= xEnd) { cs = Bytes.u64le(cd, r); r += 8 }
                    if (lo == 0xFFFFFFFFL && r + 8 <= xEnd) { lo = Bytes.u64le(cd, r) }
                }
                q += 4 + sz
            }
            if (us < 0) us = 0; if (cs < 0) cs = 0
            count++
            val isDir = name.endsWith("/")
            if (isDir) dirs++
            if (flags and 1 != 0 || method == 99) enc++
            totalC = satAdd(totalC, cs); totalU = satAdd(totalU, us)
            methods[method] = (methods[method] ?: 0) + 1
            if (dos != null && (newest == null || dos > newest)) newest = dos
            val e = Ent(name, method, flags, cs, us, lo + shift, dos)
            if (listed.size < MAX_ENTRIES_LISTED) listed += e
            when {
                name == "META-INF/MANIFEST.MF" -> manifest = e
                name == "mimetype" -> mimetype = e
                name == "AndroidManifest.xml" -> hasAndroid = true
                name == "[Content_Types].xml" -> hasContentTypes = true
                name == "META-INF/container.xml" -> hasContainerXml = true
                name.endsWith(".class") -> hasClass = true
                name.startsWith("classes") && name.endsWith(".dex") && !name.contains('/') -> hasDex = true
                !name.contains('/') && name.endsWith(".nuspec") -> nuspec = name
                name.endsWith(".dist-info/WHEEL") && name.count { it == '/' } == 1 -> wheelDir = name.substringBefore('/')
            }
            p += 46 + nl + xl + cm
        }
        if (count < entriesTotal) warnings += "Only $count of $entriesTotal declared entries could be read from the central directory"
        if (count > entriesTotal && entriesTotal != 0xFFFFL) warnings += "Central directory holds more entries ($count) than declared ($entriesTotal)"

        facts += Fact("Entries", count.toString(), "Archive")
        facts += Fact("Directories", dirs.toString(), "Archive")
        facts += Fact("Files", (count - dirs).toString(), "Archive")
        facts += Fact("Total compressed size", ArcUtil.size(totalC), "Archive")
        facts += Fact("Total uncompressed size", ArcUtil.size(totalU), "Archive")
        if (methods.isNotEmpty()) facts += Fact("Compression methods", methods.entries.joinToString(", ") { "${methodName(it.key)} x${it.value}" }, "Archive")
        facts += Fact("Encryption", if (enc > 0) "$enc entries encrypted" else "none", "Archive")
        if (newest != null) facts += Fact("Newest entry date", newest, "Archive")
        if (cl > 0) {
            val cb = ArcUtil.utf8(tail, ei + 22, minOf(cl, 4096))
            facts += Fact("Comment", cb.take(1000), "Archive")
        }

        // Flavour hints
        val hints = ArrayList<String>()
        if (hasAndroid && hasDex) hints += "Android package (AndroidManifest.xml + classes.dex)"
        else if (hasAndroid) hints += "AndroidManifest.xml present"
        if (manifest != null && (hasClass || ext == "jar")) hints += "Java archive (META-INF/MANIFEST.MF)"
        else if (manifest != null) hints += "META-INF/MANIFEST.MF present"
        if (wheelDir != null) hints += "Python wheel (${wheelDir})"
        if (nuspec != null) hints += "NuGet package ($nuspec)"
        if (hasContentTypes && nuspec == null) hints += "Open Packaging Conventions container ([Content_Types].xml)"
        if (hasContainerXml && mimetype != null) hints += "EPUB/OCF container (META-INF/container.xml)"
        if (hints.isNotEmpty()) facts += Fact("Flavour hints", hints.joinToString("; "), "Flavour")

        var text: String? = null
        if (mimetype != null) {
            val d = readEntry(source, mimetype, 256)
            if (d != null) facts += Fact("mimetype", ArcUtil.clean(String(d, Charsets.US_ASCII).trim()), "Flavour")
        }
        if (manifest != null) {
            val d = readEntry(source, manifest, 65536)
            if (d != null) {
                val m = parseManifest(String(d, Charsets.UTF_8))
                for (k in listOf("Manifest-Version", "Main-Class", "Implementation-Title", "Implementation-Version",
                    "Created-By", "Automatic-Module-Name", "Multi-Release", "Bundle-SymbolicName", "Class-Path")) {
                    m[k]?.let { facts += Fact(k, ArcUtil.clean(it).take(300), "Manifest") }
                }
                text = String(d, Charsets.UTF_8).take(FormatInspector.MAX_TEXT_CHARS)
            }
        }
        for (e in listed) {
            val desc = if (e.name.endsWith("/")) "directory"
            else "${e.usize} bytes (compressed ${e.csize}), ${methodName(e.method)}" + (if (e.flags and 1 != 0) ", encrypted" else "")
            facts += Fact(e.name.take(300), desc, "Entry list")
        }
        if (count > listed.size) facts += Fact("More entries", "${count - listed.size} not listed", "Entry list")
        if (text == null && cl > 0) text = ArcUtil.utf8(tail, ei + 22, minOf(cl, 4096))

        val label = when (fid) { "jar" -> "JAR archive"; "apk" -> "Android package"; "whl" -> "Python wheel"; "nupkg" -> "NuGet package"; else -> "ZIP archive" }
        val summary = "$label, $count entries, ${Bytes.humanSize(totalU)} uncompressed" + if (enc > 0) ", encrypted entries" else ""
        return InspectionResult(fid, label, summary, facts, warnings, text)
    }

    private fun satAdd(a: Long, b: Long): Long { val r = a + b; return if (r < 0) Long.MAX_VALUE else r }

    private fun cp437(b: ByteArray, o: Int, len: Int): String {
        val cs = try { java.nio.charset.Charset.forName("IBM437") } catch (t: Throwable) { Charsets.ISO_8859_1 }
        if (o < 0 || len <= 0 || o >= b.size) return ""
        return ArcUtil.clean(String(b, o, minOf(len, b.size - o), cs))
    }

    private fun readEntry(source: ByteSource, e: Ent, maxOut: Int): ByteArray? = ArcUtil.safe {
        if (e.flags and 1 != 0 || e.localOff < 0) return@safe null
        val h = ArcUtil.readAt(source, e.localOff, 30)
        if (!ArcUtil.eq(h, 0, "PK\u0003\u0004")) return@safe null
        val data = e.localOff + 30 + Bytes.u16le(h, 26) + Bytes.u16le(h, 28)
        when (e.method) {
            0 -> ArcUtil.readAt(source, data, ArcUtil.clampInt(minOf(e.usize, e.csize), maxOut))
            8 -> {
                val c = ArcUtil.readAt(source, data, ArcUtil.clampInt(e.csize, 256 * 1024))
                ArcUtil.inflateRaw(c, 0, c.size, maxOut)
            }
            else -> null
        }
    }

    /** Main section of a JAR manifest with continuation lines joined. */
    fun parseManifest(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var lastKey: String? = null
        for (line in s.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (line.isEmpty()) break
            if (line.startsWith(" ") && lastKey != null) { out[lastKey] = out[lastKey] + line.substring(1); continue }
            val c = line.indexOf(':')
            if (c <= 0) continue
            lastKey = line.substring(0, c)
            out[lastKey] = line.substring(c + 1).trim()
        }
        return out
    }
}
