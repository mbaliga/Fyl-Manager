package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Every binary-format inspector in this package: ELF, PE, Mach-O, Java class, DEX, WebAssembly, SQLite, pyc. */
object BinaryInspectors {
    val all: List<FormatInspector> = listOf(
        BinaryElfInspector,
        BinaryPeInspector,
        BinaryMachOInspector,
        BinaryClassInspector,
        BinaryDexInspector,
        BinaryWasmInspector,
        BinarySqliteInspector,
        BinaryPycInspector,
    )
}

internal inline fun <T> binSafe(block: () -> T?): T? = try { block() } catch (t: Throwable) { null }

/** Bounded reader: clamps every request to the file, to [MAX_CHUNK] and to the total read budget. */
internal class BinReader(private val src: ByteSource) {
    private var budget: Long = FormatInspector.MAX_READ_BYTES.toLong()
    val size: Long = maxOf(0L, src.size)

    fun read(off: Long, len: Long): ByteArray {
        if (off < 0 || len <= 0 || off >= size || budget <= 0) return ByteArray(0)
        val n = minOf(len, size - off, budget, MAX_CHUNK.toLong()).toInt()
        val r = src.read(off, n)
        budget -= r.size
        return r
    }

    /** True when [off, off+len) lies inside the file. */
    fun inFile(off: Long, len: Long): Boolean = off >= 0 && len >= 0 && off <= size && len <= size - off

    /** NUL-terminated text starting at [off], at most [max] bytes; null when nothing readable. */
    fun cstr(off: Long, max: Int = 256): String? {
        val b = read(off, max.toLong())
        if (b.isEmpty()) return null
        var n = 0
        while (n < b.size && b[n].toInt() != 0) n++
        return BinFmt.clean(String(b, 0, n, Charsets.UTF_8))
    }

    companion object { const val MAX_CHUNK = 4 * 1024 * 1024 }
}

internal class BinEndian(val le: Boolean) {
    fun u16(b: ByteArray, o: Int): Int = if (le) Bytes.u16le(b, o) else Bytes.u16be(b, o)
    fun u32(b: ByteArray, o: Int): Long = if (le) Bytes.u32le(b, o) else Bytes.u32be(b, o)
    fun u64(b: ByteArray, o: Int): Long = if (le) Bytes.u64le(b, o) else Bytes.u64be(b, o)
}

internal object BinFmt {
    private const val HEX = "0123456789abcdef"

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(HEX[v shr 4]).append(HEX[v and 15]) }
        return sb.toString()
    }

    fun hex(v: Long): String = "0x" + java.lang.Long.toHexString(v)

    /** Replace control characters so odd names cannot break a display. */
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c.code < 0x20 || c.code == 0x7F) '.' else c)
        return sb.toString()
    }

    fun time(secs: Long): String =
        try { java.time.Instant.ofEpochSecond(secs).toString() } catch (t: Throwable) { secs.toString() }

    /** Mach-O packed version nibbles: xxxx.yy.zz */
    fun packedVersion(v: Long): String = "${(v shr 16) and 0xFFFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    fun list(items: List<String>, cap: Int): String =
        if (items.size <= cap) items.joinToString(", ") else items.take(cap).joinToString(", ") + ", ... (+${items.size - cap} more)"
}
