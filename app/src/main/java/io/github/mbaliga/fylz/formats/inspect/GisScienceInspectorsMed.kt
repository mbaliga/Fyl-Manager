package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** DICOM Part 10 file: preamble, meta group, then a bounded flat walk of the data set. */
object GisScienceDicomInspector : FormatInspector {
    override val formatIds = setOf("dcm")

    private val TS = mapOf(
        "1.2.840.10008.1.2" to "Implicit VR Little Endian",
        "1.2.840.10008.1.2.1" to "Explicit VR Little Endian",
        "1.2.840.10008.1.2.1.99" to "Deflated Explicit VR Little Endian",
        "1.2.840.10008.1.2.2" to "Explicit VR Big Endian (retired)",
        "1.2.840.10008.1.2.4.50" to "JPEG Baseline (Process 1)",
        "1.2.840.10008.1.2.4.51" to "JPEG Extended (Process 2 & 4)",
        "1.2.840.10008.1.2.4.57" to "JPEG Lossless (Process 14)",
        "1.2.840.10008.1.2.4.70" to "JPEG Lossless (Process 14, SV1)",
        "1.2.840.10008.1.2.4.80" to "JPEG-LS Lossless",
        "1.2.840.10008.1.2.4.81" to "JPEG-LS Lossy (Near-Lossless)",
        "1.2.840.10008.1.2.4.90" to "JPEG 2000 Lossless Only",
        "1.2.840.10008.1.2.4.91" to "JPEG 2000",
        "1.2.840.10008.1.2.5" to "RLE Lossless")
    private val SOP = mapOf(
        "1.2.840.10008.5.1.4.1.1.1" to "CR Image Storage",
        "1.2.840.10008.5.1.4.1.1.2" to "CT Image Storage",
        "1.2.840.10008.5.1.4.1.1.2.1" to "Enhanced CT Image Storage",
        "1.2.840.10008.5.1.4.1.1.4" to "MR Image Storage",
        "1.2.840.10008.5.1.4.1.1.4.1" to "Enhanced MR Image Storage",
        "1.2.840.10008.5.1.4.1.1.6.1" to "Ultrasound Image Storage",
        "1.2.840.10008.5.1.4.1.1.7" to "Secondary Capture Image Storage",
        "1.2.840.10008.5.1.4.1.1.128" to "Positron Emission Tomography Image Storage")

    private val LONG_VR = setOf("OB", "OD", "OF", "OL", "OV", "OW", "SQ", "SV", "UC", "UN", "UR", "UT", "UV")
    private val VALID_VR = LONG_VR + setOf("AE", "AS", "AT", "CS", "DA", "DS", "DT", "FD", "FL", "IS", "LO", "LT", "PN", "SH", "SL", "SS", "ST", "TM", "UI", "UL", "US")

    // tag (group<<16|element) -> label, group, VR (used for implicit VR and to decide decoding)
    private class T(val label: String, val group: String, val vr: String)
    private val TAGS = mapOf(
        0x00080016 to T("SOP class", "Object", "UI"),
        0x00080020 to T("Study date", "Study", "DA"),
        0x00080060 to T("Modality", "Equipment", "CS"),
        0x00080070 to T("Manufacturer", "Equipment", "LO"),
        0x00081030 to T("Study description", "Study", "LO"),
        0x0008103E to T("Series description", "Study", "LO"),
        0x00081090 to T("Manufacturer model name", "Equipment", "LO"),
        0x00280002 to T("Samples per pixel", "Image", "US"),
        0x00280004 to T("Photometric interpretation", "Image", "CS"),
        0x00280008 to T("Number of frames", "Image", "IS"),
        0x00280010 to T("Rows", "Image", "US"),
        0x00280011 to T("Columns", "Image", "US"),
        0x00280100 to T("Bits allocated", "Image", "US"),
        0x00280101 to T("Bits stored", "Image", "US"),
        0x00100010 to T("Patient name (SENSITIVE)", "Patient (sensitive)", "PN"),
        0x00100020 to T("Patient ID (SENSITIVE)", "Patient (sensitive)", "LO"),
        0x00100030 to T("Patient birth date (SENSITIVE)", "Patient (sensitive)", "DA"),
        0x00100040 to T("Patient sex (SENSITIVE)", "Patient (sensitive)", "CS"))

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val b = rd.head(1 shl 20)
        if (b.size < 132 || !Bytes.startsWith(b, "DICM", 128)) return@guard null
        val f = GsFacts()
        f.add("File", "Preamble", "128 bytes + DICM prefix")
        var p = 132
        var tsUid: String? = null
        var bigEndian = false
        var explicit = true
        var applied = false
        var elements = 0
        var depth = 0
        var sawPixel = false
        val found = LinkedHashMap<Int, String>()
        var stopped = false
        loop@ while (elements < 20000 && p + 8 <= b.size) {
            elements++
            val inMeta = Bytes.u16le(b, p) == 0x0002
            if (!inMeta && !applied) {
                applied = true
                when (tsUid) {
                    "1.2.840.10008.1.2" -> explicit = false
                    "1.2.840.10008.1.2.2" -> bigEndian = true
                    "1.2.840.10008.1.2.1.99" -> { f.warn("Deflated data set: elements after the meta header are not read"); stopped = true; break@loop }
                    null -> f.warn("No transfer syntax in the meta header; assuming explicit VR little endian")
                }
            }
            val le = inMeta || !bigEndian
            val grp = if (le) Bytes.u16le(b, p) else Bytes.u16be(b, p)
            val el = if (le) Bytes.u16le(b, p + 2) else Bytes.u16be(b, p + 2)
            val tag = (grp shl 16) or el
            if (grp == 0xFFFE) {
                // item / delimiters carry no VR and a 4-byte length
                val len = if (le) Bytes.u32le(b, p + 4) else Bytes.u32be(b, p + 4)
                if (tag == 0xFFFEE000.toInt() && len != 0xFFFFFFFFL) {
                    if (p + 8 + len > b.size) break@loop
                    p += 8 + len.toInt()
                } else {
                    if (tag == 0xFFFEE0DD.toInt() && depth > 0) depth--
                    p += 8
                }
                continue
            }
            val vr: String; val len: Long; val vp: Int
            if (inMeta || explicit) {
                vr = Bytes.ascii(b, p + 4, 2)
                if (vr !in VALID_VR) { f.warn("Invalid VR at offset $p; data set walk stopped"); stopped = true; break }
                if (vr in LONG_VR) { len = if (le) Bytes.u32le(b, p + 8) else Bytes.u32be(b, p + 8); vp = p + 12 }
                else { len = if (le) Bytes.u16le(b, p + 6).toLong() else Bytes.u16be(b, p + 6).toLong(); vp = p + 8 }
            } else {
                vr = TAGS[tag]?.vr ?: "UN"
                len = if (le) Bytes.u32le(b, p + 4) else Bytes.u32be(b, p + 4)
                vp = p + 8
            }
            handle(tag, vr, len, vp, b, le, f, found, depth)
            if (tag == 0x7FE00010) { sawPixel = true; break }
            if (tag == 0x00020010 && len in 1..128) tsUid = Bytes.ascii(b, vp, len.toInt()).trimEnd('.', ' ')
            if (len == 0xFFFFFFFFL) { depth++; p = vp; continue }
            if (vp.toLong() + len > b.size) { f.warn("Element ${tagStr(tag)} extends past the examined region"); break }
            p = vp + len.toInt()
        }
        tsUid?.let { f.add("Transfer syntax", "Transfer syntax UID", it); f.add("Transfer syntax", "Transfer syntax name", TS[it] ?: "unrecognised") }
        for ((tag, v) in found) {
            val t = TAGS[tag] ?: continue
            var value = v
            if (tag == 0x00080016) SOP[v]?.let { value = "$v ($it)" }
            f.add(t.group, t.label, value)
        }
        if (found[0x00280008] == null && sawPixel) f.add("Image", "Number of frames", "1 (attribute absent)")
        if (sawPixel) f.add("Image", "Pixel data", "present")
        else if (!stopped) f.warn("Pixel data element not reached in the examined region")
        if (found.keys.any { (it ushr 16) == 0x0010 }) f.warn("Contains patient identifying fields (marked SENSITIVE)")
        val modality = found[0x00080060]
        val rows = found[0x00280010]; val cols = found[0x00280011]
        InspectionResult("dcm", "DICOM object",
            listOfNotNull(modality, if (rows != null && cols != null) "${cols} x $rows" else null).joinToString(", ").ifEmpty { null },
            f.facts, f.warnings)
    }

    private fun tagStr(t: Int) = "(%04X,%04X)".format(t ushr 16, t and 0xFFFF)

    private fun handle(tag: Int, vr: String, len: Long, vp: Int, b: ByteArray, le: Boolean, f: GsFacts, found: MutableMap<Int, String>, depth: Int): Boolean {
        if (depth > 0) return false
        val t = TAGS[tag] ?: return false
        if (len == 0xFFFFFFFFL || len > 256 || vp + len > b.size) return false
        val n = len.toInt()
        val v = if (vr == "US" || t.vr == "US") {
            if (n >= 2) (if (le) Bytes.u16le(b, vp) else Bytes.u16be(b, vp)).toString() else return false
        } else {
            String(b, vp, n, Charsets.ISO_8859_1).trimEnd(' ', '\u0000').trim().replace('^', ' ').let { if (tag == 0x00100010) it.trim() else it }
        }
        if (v.isNotEmpty() && !found.containsKey(tag)) found[tag] = v
        return true
    }
}
