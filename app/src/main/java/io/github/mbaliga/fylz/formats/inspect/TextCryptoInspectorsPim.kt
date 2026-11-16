package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** One unfolded content line "NAME;PARAM=x:value" (RFC 6350 / RFC 5545). */
internal class ContentLine(val name: String, val params: Map<String, String>, val value: String)

internal object ContentLines {
    fun unfold(text: String, maxLines: Int = 200_000): List<String> {
        val out = ArrayList<String>()
        var pos = 0
        var cur: StringBuilder? = null
        while (pos <= text.length && out.size < maxLines) {
            var eol = text.indexOf('\n', pos)
            if (eol < 0) eol = text.length
            var le = eol
            if (le > pos && text[le - 1] == '\r') le--
            if (le > pos && (text[pos] == ' ' || text[pos] == '\t') && cur != null) {
                if (cur.length < 100_000) cur.append(text, pos + 1, le)
            } else {
                if (cur != null) out.add(cur.toString())
                cur = StringBuilder(text.substring(pos, le))
            }
            if (eol >= text.length) break
            pos = eol + 1
        }
        if (cur != null && out.size < maxLines) out.add(cur.toString())
        return out
    }

    fun parse(line: String): ContentLine? {
        var q = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') q = !q
            else if (c == ':' && !q) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val pieces = ArrayList<String>()
        val cur = StringBuilder()
        var qq = false
        for (c in head) {
            if (c == '"') qq = !qq
            if (c == ';' && !qq) { pieces.add(cur.toString()); cur.setLength(0) } else cur.append(c)
        }
        pieces.add(cur.toString())
        var name = pieces[0].trim()
        if (name.contains('.')) name = name.substringAfterLast('.')
        val params = LinkedHashMap<String, String>()
        for (p in pieces.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).trim().uppercase()] = p.substring(eq + 1).trim().trim('"')
        }
        return ContentLine(name.uppercase(), params, value)
    }

    fun unescape(v: String): String {
        if (!v.contains('\\')) return v
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                val n = v[i + 1]
                sb.append(if (n == 'n' || n == 'N') '\n' else n); i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}

/** vCard (.vcf): contact count, names and organisations. */
object VcfInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("vcf-vcard")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private class Card { var fn: String? = null; var n: String? = null; var org: String? = null; var email = 0; var tel = 0 }

    private fun run(source: ByteSource): InspectionResult? {
        val data = TC.readUpTo(source, 4 shl 20)
        val text = TC.utf8(data)
        if (!text.contains("BEGIN:VCARD", ignoreCase = true)) return null
        val cards = ArrayList<Card>()
        val versions = LinkedHashSet<String>()
        var cur: Card? = null
        var total = 0
        for (raw in ContentLines.unfold(text)) {
            val cl = ContentLines.parse(raw) ?: continue
            when (cl.name) {
                "BEGIN" -> if (cl.value.trim().equals("VCARD", true)) { cur = Card(); total++ }
                "END" -> if (cl.value.trim().equals("VCARD", true)) { cur?.let { if (cards.size < 1000) cards.add(it) }; cur = null }
                else -> {
                    val c = cur ?: continue
                    when (cl.name) {
                        "VERSION" -> versions.add(cl.value.trim())
                        "FN" -> if (c.fn == null) c.fn = ContentLines.unescape(cl.value).trim()
                        "N" -> if (c.n == null) {
                            val p = cl.value.split(';').map { ContentLines.unescape(it).trim() }
                            c.n = listOf(p.getOrNull(3), p.getOrNull(1), p.getOrNull(2), p.getOrNull(0), p.getOrNull(4))
                                .filter { !it.isNullOrEmpty() }.joinToString(" ")
                        }
                        "ORG" -> if (c.org == null) c.org = cl.value.split(';').map { ContentLines.unescape(it).trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                        "EMAIL" -> c.email++
                        "TEL" -> c.tel++
                    }
                }
            }
        }
        if (total == 0) return null
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        facts.add(Fact("Contacts", total.toString(), "Address book"))
        if (versions.isNotEmpty()) facts.add(Fact("vCard version", versions.joinToString(", "), "Address book"))
        facts.add(Fact("Email addresses", cards.sumOf { it.email }.toString(), "Address book"))
        facts.add(Fact("Phone numbers", cards.sumOf { it.tel }.toString(), "Address book"))
        for ((i, c) in cards.take(20).withIndex()) {
            val name = c.fn?.takeIf { it.isNotEmpty() } ?: c.n?.takeIf { it.isNotEmpty() } ?: "(unnamed)"
            facts.add(Fact("Contact ${i + 1}", TC.cap(name, 120) + (if (!c.org.isNullOrEmpty()) " (${TC.cap(c.org!!, 120)})" else ""), "Contacts"))
        }
        if (TC.truncated(source, data)) warnings.add("File larger than the read limit; counts cover only the first part")
        if (cards.size < total) warnings.add("Some cards are missing END:VCARD")
        return InspectionResult("vcf", "vCard contacts", "$total contact${if (total == 1) "" else "s"}", facts, warnings)
    }
}

/** iCalendar (.ics): events, summaries, dates and time zones. */
object IcsInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("ics")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source) }

    private class Event { var summary: String? = null; var start: String? = null; var end: String? = null; var rrule = false }

    private val dateRe = Regex("^(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})?(Z)?)?$")

    internal fun fmtDate(value: String, tzid: String?): String {
        val m = dateRe.find(value.trim()) ?: return value.trim()
        val g = m.groupValues
        var s = "${g[1]}-${g[2]}-${g[3]}"
        if (g[4].isNotEmpty()) s += " ${g[4]}:${g[5]}:${if (g[6].isEmpty()) "00" else g[6]}"
        if (g[7].isNotEmpty()) s += " UTC" else if (tzid != null && g[4].isNotEmpty()) s += " ($tzid)"
        return s
    }

    private fun run(source: ByteSource): InspectionResult? {
        val data = TC.readUpTo(source, 4 shl 20)
        val text = TC.utf8(data)
        if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) return null
        val events = ArrayList<Event>()
        val stack = ArrayList<String>()
        var cur: Event? = null
        val tzids = LinkedHashSet<String>()
        var todos = 0; var journals = 0; var eventCount = 0; var alarms = 0
        var prodid: String? = null; var version: String? = null; var calName: String? = null
        var method: String? = null; var calscale: String? = null; var xTz: String? = null
        for (raw in ContentLines.unfold(text)) {
            val cl = ContentLines.parse(raw) ?: continue
            if (cl.name == "BEGIN") {
                val comp = cl.value.trim().uppercase()
                if (stack.size < 32) stack.add(comp)
                when (comp) {
                    "VEVENT" -> { eventCount++; cur = Event() }
                    "VTODO" -> todos++
                    "VJOURNAL" -> journals++
                    "VALARM" -> alarms++
                }
                continue
            }
            if (cl.name == "END") {
                val comp = cl.value.trim().uppercase()
                if (comp == "VEVENT") { cur?.let { if (events.size < 5000) events.add(it) }; cur = null }
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }
            val top = stack.lastOrNull()
            when (top) {
                "VCALENDAR" -> when (cl.name) {
                    "PRODID" -> prodid = cl.value.trim()
                    "VERSION" -> version = cl.value.trim()
                    "METHOD" -> method = cl.value.trim()
                    "CALSCALE" -> calscale = cl.value.trim()
                    "X-WR-CALNAME" -> calName = ContentLines.unescape(cl.value).trim()
                    "X-WR-TIMEZONE" -> xTz = cl.value.trim()
                }
                "VTIMEZONE" -> if (cl.name == "TZID") tzids.add(cl.value.trim())
                "VEVENT" -> {
                    val e = cur ?: continue
                    when (cl.name) {
                        "SUMMARY" -> if (e.summary == null) e.summary = ContentLines.unescape(cl.value).trim()
                        "DTSTART" -> if (e.start == null) e.start = fmtDate(cl.value, cl.params["TZID"])
                        "DTEND" -> if (e.end == null) e.end = fmtDate(cl.value, cl.params["TZID"])
                        "RRULE" -> e.rrule = true
                    }
                }
            }
        }
        if (stack.isEmpty() && eventCount == 0 && todos == 0 && journals == 0 && version == null && prodid == null) return null
        val facts = ArrayList<Fact>()
        prodid?.let { facts.add(Fact("Product", TC.cap(it, 200), "Calendar")) }
        version?.let { facts.add(Fact("iCalendar version", it, "Calendar")) }
        calName?.let { facts.add(Fact("Name", TC.cap(it, 200), "Calendar")) }
        method?.let { facts.add(Fact("Method", it, "Calendar")) }
        calscale?.let { facts.add(Fact("Calendar scale", it, "Calendar")) }
        val zones = (tzids.toList() + listOfNotNull(xTz)).distinct()
        if (zones.isNotEmpty()) facts.add(Fact("Time zones", zones.take(10).joinToString(", "), "Calendar"))
        facts.add(Fact("Events", eventCount.toString(), "Contents"))
        if (todos > 0) facts.add(Fact("To-dos", todos.toString(), "Contents"))
        if (journals > 0) facts.add(Fact("Journal entries", journals.toString(), "Contents"))
        if (alarms > 0) facts.add(Fact("Alarms", alarms.toString(), "Contents"))
        val recurring = events.count { it.rrule }
        if (recurring > 0) facts.add(Fact("Recurring events", recurring.toString(), "Contents"))
        val starts = events.mapNotNull { it.start }.sorted()
        if (starts.isNotEmpty()) {
            facts.add(Fact("Earliest start", starts.first(), "Contents"))
            facts.add(Fact("Latest start", starts.last(), "Contents"))
        }
        for ((i, e) in events.take(20).withIndex()) {
            val range = listOfNotNull(e.start, e.end).joinToString(" to ")
            facts.add(Fact("Event ${i + 1}", TC.cap(e.summary ?: "(no summary)", 160) + (if (range.isNotEmpty()) " - $range" else ""), "Events"))
        }
        val warnings = ArrayList<String>()
        if (TC.truncated(source, data)) warnings.add("File larger than the read limit; counts cover only the first part")
        return InspectionResult("ics", calName ?: "iCalendar", "$eventCount event${if (eventCount == 1) "" else "s"}", facts, warnings)
    }
}
