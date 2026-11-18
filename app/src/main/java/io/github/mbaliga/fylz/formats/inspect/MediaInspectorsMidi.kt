package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Standard MIDI File: header, per-track names, tempo map duration, note counts. */
object MediaInspectorsMidi : FormatInspector {
    override val formatIds: Set<String> = setOf("mid")
    private val U = MediaInspectorsUtil

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? =
        try { run(source) } catch (t: Throwable) { null }

    private val KEYS = arrayOf("Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#")
    private val KEYS_MINOR = arrayOf("Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#")

    private fun readVar(b: ByteArray, p: IntArray, end: Int): Long {
        var v = 0L
        for (i in 0 until 4) {
            if (p[0] >= end) return -1
            val x = Bytes.u8(b, p[0]++)
            v = (v shl 7) or (x and 0x7F).toLong()
            if ((x and 0x80) == 0) return v
        }
        return v
    }

    private class Trk { var name = ""; var notes = 0L; var events = 0L; var endTick = 0L; val channels = java.util.TreeSet<Int>() }

    private fun run(src: ByteSource): InspectionResult? {
        val size = src.size
        val b = U.readRange(src, 0, 4L * 1024 * 1024)
        if (b.size < 14 || !Bytes.startsWith(b, "MThd")) return null
        val hlen = Bytes.u32be(b, 4)
        if (hlen < 6 || hlen > 1024) return null
        val format = Bytes.u16be(b, 8)
        val ntrks = Bytes.u16be(b, 10)
        val div = Bytes.u16be(b, 12)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (size > b.size) warnings.add("File is larger than 4 MiB; only the first 4 MiB were analysed, so duration and counts are partial")
        val g = "Header"
        facts.add(Fact("Format", when (format) { 0 -> "0 (single track)"; 1 -> "1 (simultaneous tracks)"; 2 -> "2 (independent sequences)"; else -> "$format (unknown)" }, g))
        facts.add(Fact("Track count (header)", ntrks.toString(), g))
        val smpte = (div and 0x8000) != 0
        var tpq = 0
        if (smpte) {
            val fpsRaw = 256 - (div shr 8)
            val fpsTxt = when (fpsRaw) { 24 -> "24"; 25 -> "25"; 29 -> "29.97 (drop-frame)"; 30 -> "30"; else -> null }
            if (fpsTxt != null) facts.add(Fact("Division", "SMPTE, $fpsTxt fps, ${div and 0xFF} ticks/frame", g))
            else facts.add(Fact("Division", "SMPTE, invalid frame rate code ${div shr 8}", g))
        } else {
            tpq = div
            facts.add(Fact("Division", "$div ticks per quarter note", g))
            if (div == 0) warnings.add("Division is zero")
        }
        var p = (8 + hlen).toInt()
        val tempos = ArrayList<LongArray>() // tick, usPerQuarter
        val trks = ArrayList<Trk>()
        var timeSig: String? = null
        var key: String? = null
        var totalEvents = 0L
        var chunks = 0
        var truncated = false
        while (p + 8 <= b.size && chunks++ < 1000 && trks.size < 256) {
            val id = Bytes.ascii(b, p, 4)
            val len = Bytes.u32be(b, p + 4)
            val s = p + 8
            val avail = (b.size - s).toLong()
            val e = if (len > avail) { truncated = true; b.size } else (s + len.toInt())
            if (id == "MTrk") {
                val t = Trk()
                val q = intArrayOf(s)
                var tick = 0L
                var running = 0
                var guard = 0
                while (q[0] < e && guard++ < 500_000 && totalEvents < 2_000_000) {
                    val d = readVar(b, q, e)
                    if (d < 0) break
                    tick += d
                    if (q[0] >= e) break
                    var st = Bytes.u8(b, q[0])
                    if (st >= 0x80) { q[0]++ ; if (st < 0xF0) running = st } else { st = running; if (st == 0) break }
                    t.events++; totalEvents++
                    if (st == 0xFF) {
                        if (q[0] >= e) break
                        val mt = Bytes.u8(b, q[0]++)
                        val ml = readVar(b, q, e)
                        if (ml < 0 || ml > e - q[0]) break
                        val o = q[0]
                        when (mt) {
                            0x03 -> if (t.name.isEmpty()) t.name = U.clean(U.latin1(b, o, minOf(ml.toInt(), 200)), 200)
                            0x51 -> if (ml == 3L && tempos.size < 10000) tempos.add(longArrayOf(tick, ((Bytes.u8(b, o) shl 16) or (Bytes.u8(b, o + 1) shl 8) or Bytes.u8(b, o + 2)).toLong()))
                            0x58 -> if (ml >= 2 && timeSig == null) timeSig = "${Bytes.u8(b, o)}/${1 shl minOf(Bytes.u8(b, o + 1), 10)}"
                            0x59 -> if (ml == 2L && key == null) {
                                val sf = b[o].toInt()
                                val minor = Bytes.u8(b, o + 1) == 1
                                if (sf in -7..7) key = (if (minor) KEYS_MINOR else KEYS)[sf + 7] + (if (minor) " minor" else " major")
                            }
                            0x2F -> { q[0] = o + ml.toInt(); t.endTick = tick; break }
                        }
                        q[0] = o + ml.toInt()
                    } else if (st == 0xF0 || st == 0xF7) {
                        val l = readVar(b, q, e)
                        if (l < 0 || l > e - q[0]) break
                        q[0] += l.toInt()
                    } else {
                        val need = if (st in 0xC0..0xDF) 1 else 2
                        if (q[0] + need > e) break
                        val ch = st and 0x0F
                        if ((st and 0xF0) == 0x90 && Bytes.u8(b, q[0] + 1) > 0) { t.notes++; t.channels.add(ch) }
                        else if ((st and 0xF0) == 0x90 || (st and 0xF0) == 0x80) t.channels.add(ch)
                        q[0] += need
                    }
                    t.endTick = tick
                }
                trks.add(t)
            }
            if (len > avail) break
            p = s + len.toInt()
        }
        if (truncated) warnings.add("File ends inside a track chunk")
        if (trks.size != ntrks) warnings.add("Header declares $ntrks tracks but ${trks.size} were found")
        facts.add(Fact("Track chunks found", trks.size.toString(), g))
        val notes = trks.sumOf { it.notes }
        facts.add(Fact("Note-on events", notes.toString(), "Content"))
        val channels = java.util.TreeSet<Int>()
        trks.forEach { channels.addAll(it.channels) }
        if (channels.isNotEmpty()) facts.add(Fact("Channels used", channels.joinToString(", ") { (it + 1).toString() }, "Content"))
        timeSig?.let { facts.add(Fact("Time signature", it, "Content")) }
        key?.let { facts.add(Fact("Key signature", it, "Content")) }
        var seconds = -1.0
        if (tpq > 0 && format != 2) {
            val sorted = tempos.sortedBy { it[0] }
            val endTick = trks.maxOfOrNull { it.endTick } ?: 0L
            var us = 0.0
            var lastTick = 0L
            var tempo = 500_000L
            for (t in sorted) {
                if (t[0] > endTick) break
                us += (t[0] - lastTick).toDouble() * tempo / tpq
                lastTick = t[0]; tempo = t[1]
            }
            us += (endTick - lastTick).toDouble() * tempo / tpq
            seconds = us / 1e6
            facts.add(Fact("Duration", U.duration(seconds), "Content"))
            val first = sorted.lastOrNull { it[0] == 0L }
            facts.add(Fact("Initial tempo", U.num(60_000_000.0 / (first?.get(1) ?: 500_000L), 1) + " BPM" + (if (first == null) " (default)" else ""), "Content"))
            if (sorted.size > 1) facts.add(Fact("Tempo changes", sorted.size.toString(), "Content"))
        } else if (smpte && format != 2) {
            val fps = when (256 - (div shr 8)) { 24 -> 24.0; 25 -> 25.0; 29 -> 29.97; 30 -> 30.0; else -> 0.0 }
            val tpf = (div and 0xFF).toDouble()
            val endTick = trks.maxOfOrNull { it.endTick } ?: 0L
            if (fps > 0 && tpf > 0) { seconds = endTick / (fps * tpf); facts.add(Fact("Duration", U.duration(seconds), "Content")) }
        }
        val named = trks.withIndex().filter { it.value.name.isNotEmpty() }.take(32)
        for ((i, t) in named) facts.add(Fact("Track ${i + 1}", t.name + (if (t.notes > 0) " (${t.notes} notes)" else ""), "Tracks"))
        val summary = "Format $format, ${trks.size} track(s)" + (if (seconds >= 0) ", " + U.duration(seconds) else "")
        val title = trks.firstOrNull { it.name.isNotEmpty() && it.events > 0 }?.name ?: "MIDI sequence"
        return InspectionResult("mid", title, summary, facts, warnings)
    }
}
