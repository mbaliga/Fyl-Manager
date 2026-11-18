package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

internal sealed class BNode(val start: Int, val end: Int)
internal class BInt(val v: Long, s: Int, e: Int) : BNode(s, e)
internal class BStr(val b: ByteArray, val off: Int, val len: Int, s: Int, e: Int) : BNode(s, e) {
    fun text(max: Int = 2000): String = String(b, off, minOf(len, max), Charsets.UTF_8)
}
internal class BList(val items: List<BNode>, s: Int, e: Int) : BNode(s, e)
internal class BDict(val map: LinkedHashMap<String, BNode>, s: Int, e: Int) : BNode(s, e)

internal class BencodeError : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/** Bounded bencode parser that remembers each node's exact byte range. */
internal class Bencode(private val b: ByteArray) {
    var pos = 0
    private var nodes = 0

    fun parse(depth: Int = 0): BNode {
        if (depth > 32 || ++nodes > 200_000 || pos >= b.size) throw BencodeError()
        val start = pos
        val c = b[pos].toInt().toChar()
        when {
            c == 'i' -> {
                pos++
                val s = pos
                while (pos < b.size && b[pos].toInt().toChar() != 'e') { pos++; if (pos - s > 20) throw BencodeError() }
                if (pos >= b.size) throw BencodeError()
                val txt = String(b, s, pos - s, Charsets.ISO_8859_1)
                pos++
                val v = txt.toLongOrNull() ?: if (txt.matches(Regex("-?\\d+"))) Long.MAX_VALUE else throw BencodeError()
                return BInt(v, start, pos)
            }
            c == 'l' -> {
                pos++
                val items = ArrayList<BNode>()
                while (true) {
                    if (pos >= b.size) throw BencodeError()
                    if (b[pos].toInt().toChar() == 'e') { pos++; break }
                    items.add(parse(depth + 1))
                }
                return BList(items, start, pos)
            }
            c == 'd' -> {
                pos++
                val m = LinkedHashMap<String, BNode>()
                while (true) {
                    if (pos >= b.size) throw BencodeError()
                    if (b[pos].toInt().toChar() == 'e') { pos++; break }
                    val k = parse(depth + 1) as? BStr ?: throw BencodeError()
                    val v = parse(depth + 1)
                    m[String(k.b, k.off, minOf(k.len, 200), Charsets.ISO_8859_1)] = v
                }
                return BDict(m, start, pos)
            }
            c in '0'..'9' -> {
                var len = 0L
                var digits = 0
                while (pos < b.size && b[pos].toInt().toChar() in '0'..'9') {
                    len = len * 10 + (b[pos] - '0'.code.toByte()); pos++
                    if (++digits > 10) throw BencodeError()
                }
                if (pos >= b.size || b[pos].toInt().toChar() != ':') throw BencodeError()
                pos++
                if (len > b.size - pos) throw BencodeError()
                val off = pos
                pos += len.toInt()
                return BStr(b, off, len.toInt(), start, pos)
            }
            else -> throw BencodeError()
        }
    }
}

/** BitTorrent metainfo (.torrent), including the info-hash over the exact info dictionary bytes. */
object TorrentInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("torrent")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private fun BDict.str(k: String): String? = (map[k] as? BStr)?.text()
    private fun BDict.int(k: String): Long? = (map[k] as? BInt)?.v

    private fun sat(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun v2Files(node: BDict, prefix: String, out: MutableList<Pair<String, Long>>, depth: Int) {
        if (depth > 8 || out.size >= 5000) return
        for ((k, v) in node.map) {
            if (v !is BDict) continue
            if (k.isEmpty()) continue
            val meta = v.map[""] as? BDict
            if (meta != null && meta.map["length"] is BInt) out.add((prefix + k) to (meta.int("length") ?: 0))
            else v2Files(v, "$prefix$k/", out, depth + 1)
        }
    }

    private fun run(source: ByteSource): InspectionResult? {
        val data = TC.readUpTo(source, FormatInspector.MAX_READ_BYTES)
        if (data.isEmpty() || data[0].toInt() != 'd'.code) return null
        val truncated = TC.truncated(source, data)
        val root = try { Bencode(data).let { p -> (p.parse() as? BDict)?.let { it to p.pos } } } catch (e: BencodeError) { null } ?: run {
            if (truncated) return InspectionResult("torrent", "BitTorrent metainfo", null, emptyList(),
                listOf("File larger than the read limit; it could not be parsed in full"))
            return null
        }
        val dict = root.first
        val info = dict.map["info"] as? BDict ?: return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (root.second != data.size) warnings.add("${data.size - root.second} trailing bytes after the bencoded dictionary")
        val name = info.str("name")
        if (name != null) facts.add(Fact("Name", name, "Torrent"))
        val metaVersion = info.int("meta version")
        facts.add(Fact("Protocol", when (metaVersion) { 2L -> if (info.map.containsKey("pieces")) "BitTorrent v1 + v2 (hybrid)" else "BitTorrent v2"; else -> "BitTorrent v1" }, "Torrent"))
        val files = ArrayList<Pair<String, Long>>()
        var total = 0L
        val fl = info.map["files"] as? BList
        if (fl != null) {
            for (f in fl.items.take(100_000)) {
                val fd = f as? BDict ?: continue
                val len = fd.int("length") ?: 0L
                val pl = (fd.map["path.utf-8"] ?: fd.map["path"]) as? BList
                val path = pl?.items?.take(64)?.mapNotNull { (it as? BStr)?.text(500) }?.joinToString("/") ?: "?"
                total = sat(total, len)
                if (files.size < 5000) files.add(path to len)
            }
        } else if (info.map["length"] is BInt) {
            total = info.int("length") ?: 0L
            files.add((name ?: "?") to total)
        } else if (info.map["file tree"] is BDict) {
            v2Files(info.map["file tree"] as BDict, "", files, 0)
            for (f in files) total = sat(total, f.second)
        }
        if (files.isNotEmpty() || total > 0) {
            val count = fl?.items?.size ?: files.size
            facts.add(Fact("Files", count.toString(), "Contents"))
            facts.add(Fact("Total size", Bytes.humanSize(total) + " ($total bytes)", "Contents"))
            for ((i, f) in files.take(20).withIndex()) facts.add(Fact("File ${i + 1}", TC.cap(f.first, 300) + " (" + Bytes.humanSize(f.second) + ")", "Contents"))
        }
        info.int("piece length")?.let { facts.add(Fact("Piece length", Bytes.humanSize(it), "Torrent")) }
        (info.map["pieces"] as? BStr)?.let {
            facts.add(Fact("Pieces", (it.len / 20).toString(), "Torrent"))
            if (it.len % 20 != 0) warnings.add("pieces length is not a multiple of 20")
        }
        facts.add(Fact("Private", if (info.int("private") == 1L) "yes" else "no", "Torrent"))
        val trackers = LinkedHashSet<String>()
        dict.str("announce")?.let { trackers.add(it) }
        (dict.map["announce-list"] as? BList)?.items?.take(200)?.forEach { tier ->
            (tier as? BList)?.items?.take(200)?.forEach { t -> (t as? BStr)?.text(500)?.let { trackers.add(it) } }
        }
        if (trackers.isNotEmpty()) {
            facts.add(Fact("Trackers", trackers.size.toString(), "Network"))
            for ((i, t) in trackers.take(10).withIndex()) facts.add(Fact("Tracker ${i + 1}", t, "Network"))
        }
        when (val ul = dict.map["url-list"]) {
            is BStr -> facts.add(Fact("Web seed", ul.text(500), "Network"))
            is BList -> ul.items.take(5).forEach { (it as? BStr)?.let { s -> facts.add(Fact("Web seed", s.text(500), "Network")) } }
            else -> {}
        }
        dict.int("creation date")?.let { facts.add(Fact("Created", TC.fmtEpoch(it), "Torrent")) }
        dict.str("created by")?.let { facts.add(Fact("Created by", TC.cap(it, 200), "Torrent")) }
        dict.str("comment")?.let { facts.add(Fact("Comment", TC.cap(it, 500), "Torrent")) }
        val len = info.end - info.start
        val h1 = TC.hex(TC.digest("SHA-1", data, info.start, len))
        if (info.map.containsKey("pieces") || fl != null || info.map["length"] is BInt) {
            facts.add(Fact("Info hash (SHA-1)", h1, "Identity"))
            facts.add(Fact("Magnet link", "magnet:?xt=urn:btih:$h1", "Identity"))
        }
        if (metaVersion == 2L) facts.add(Fact("Info hash (SHA-256, v2)", TC.hex(TC.digest("SHA-256", data, info.start, len)), "Identity"))
        return InspectionResult("torrent", name ?: "BitTorrent metainfo",
            if (total > 0) "${files.size.coerceAtLeast(fl?.items?.size ?: 0)} file(s), ${Bytes.humanSize(total)}" else null, facts, warnings)
    }
}
