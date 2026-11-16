package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Compiled Python bytecode (.pyc). */
object BinaryPycInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("pyc")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = binSafe { run(source) }

    /** Magic number (the little-endian u16 before "\r\n") to a CPython version. Python 3 uses ranges, Python 2 exact values. */
    fun pythonVersion(magic: Int): String? = when (magic) {
        62211 -> "2.7"; 62161 -> "2.6"; 62131 -> "2.5"; 62061 -> "2.4"; 62011 -> "2.3"; 60717 -> "2.2"
        in 3000..3131 -> "3.0"
        in 3141..3159 -> "3.1"
        in 3160..3189 -> "3.2"
        in 3190..3249 -> "3.3"
        in 3250..3319 -> "3.4"
        in 3320..3359 -> "3.5"
        in 3360..3389 -> "3.6"
        in 3390..3399 -> "3.7"
        in 3400..3419 -> "3.8"
        in 3420..3429 -> "3.9"
        in 3430..3449 -> "3.10"
        in 3450..3499 -> "3.11"
        in 3500..3549 -> "3.12"
        in 3550..3599 -> "3.13"
        in 3600..3649 -> "3.14"
        else -> null
    }

    private fun run(source: ByteSource): InspectionResult? {
        val rd = BinReader(source)
        val h = rd.read(0, 32)
        if (h.size < 4 || h[2].toInt() != 0x0D || h[3].toInt() != 0x0A) return null
        val magic = Bytes.u16le(h, 0)
        val ver = pythonVersion(magic) ?: return null
        val py3 = ver.startsWith("3")
        val minor = if (py3) ver.substringAfter('.').toInt() else -1
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts += Fact("Magic number", magic.toString(), "Header")
        facts += Fact("Python version", "CPython $ver", "Header")

        var headerLen: Int
        when {
            !py3 || minor <= 2 -> {
                headerLen = 8
                if (h.size >= 8) facts += Fact("Source modified", stamp(Bytes.u32le(h, 4)), "Header")
            }
            minor <= 6 -> {
                headerLen = 12
                if (h.size >= 12) {
                    facts += Fact("Source modified", stamp(Bytes.u32le(h, 4)), "Header")
                    facts += Fact("Source size", "${Bytes.u32le(h, 8)} bytes", "Header")
                }
            }
            else -> {
                headerLen = 16
                if (h.size >= 16) {
                    val flags = Bytes.u32le(h, 4)
                    val hashBased = flags and 1L != 0L
                    facts += Fact("Flags", BinFmt.hex(flags), "Header")
                    facts += Fact("Invalidation mode", if (hashBased) (if (flags and 2L != 0L) "checked hash" else "unchecked hash") else "timestamp", "Header")
                    if (hashBased) {
                        facts += Fact("Source hash (SipHash)", BinFmt.hex(h.copyOfRange(8, 16)), "Header")
                    } else {
                        facts += Fact("Source modified", stamp(Bytes.u32le(h, 8)), "Header")
                        facts += Fact("Source size", "${Bytes.u32le(h, 12)} bytes", "Header")
                    }
                    if (flags and 3L.inv() != 0L) warnings += "Unknown flag bits set: ${BinFmt.hex(flags and 3L.inv())}"
                }
            }
        }
        if (h.size < headerLen) warnings += "Header is truncated ($headerLen bytes expected)"
        else {
            val body = rd.read(headerLen.toLong(), 1)
            if (body.isNotEmpty()) {
                val code = body[0].toInt() and 0xFF
                val t = (code and 0x7F).toChar()
                facts += Fact("Marshalled object", if (t == 'c') "code object (type 'c'${if (code and 0x80 != 0) ", ref flag set" else ""})" else "type '${if (t.code in 0x20..0x7E) t else '?'}' (not a code object)", "Body")
                if (t != 'c') warnings += "The marshalled payload does not start with a code object"
            } else warnings += "File ends after the header"
        }
        val title = "Python bytecode (CPython $ver)"
        return InspectionResult("pyc", title, title, facts, warnings)
    }

    private fun stamp(v: Long): String = if (v == 0L) "not set" else BinFmt.time(v)
}
