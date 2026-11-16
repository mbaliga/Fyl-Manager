package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*

/** Internet shortcuts (.url), macOS web locations (.webloc) and freedesktop launchers (.desktop). */
object ShortcutInspector : FormatInspector {
    override val formatIds: Set<String> = setOf("url", "webloc", "desktop")
    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = TC.guard { run(source, fileName) }

    /** Linear scan for <key>URL</key><string>...</string>; only the first key occurrence is considered. */
    private fun weblocUrl(text: String): String? {
        val key = "<key>URL</key>"
        val k = text.indexOf(key)
        if (k < 0) return null
        var p = k + key.length
        while (p < text.length && text[p].isWhitespace()) p++
        if (!text.startsWith("<string>", p)) return null
        p += 8
        val e = text.indexOf("</string>", p)
        return if (e < 0) null else text.substring(p, e)
    }

    private fun ini(text: String): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var cur: LinkedHashMap<String, String>? = null
        var n = 0
        for (raw in text.split('\n')) {
            if (++n > 5000) break
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) continue
            if (l.startsWith("[") && l.endsWith("]")) { cur = out.getOrPut(l.substring(1, l.length - 1)) { LinkedHashMap() }; continue }
            val eq = l.indexOf('=')
            if (eq > 0 && cur != null) cur.putIfAbsent(l.substring(0, eq).trim(), l.substring(eq + 1).trim())
        }
        return out
    }

    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun run(source: ByteSource, fileName: String?): InspectionResult? {
        val data = TC.readUpTo(source, 256 * 1024)
        if (data.isEmpty()) return null
        val baseName = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.', "")?.ifEmpty { null }
        if (Bytes.startsWith(data, "bplist")) {
            return InspectionResult("webloc", "Web location (binary plist)", null,
                listOf(Fact("Format", "Binary property list", "Shortcut")), listOf("Binary property lists are not decoded; the URL is not shown"))
        }
        val text = TC.utf8(data)
        val facts = ArrayList<Fact>()
        val warnings = ArrayList<String>()
        if (text.contains("[Desktop Entry]")) {
            val sec = ini(text)["Desktop Entry"] ?: return null
            val name = sec["Name"] ?: baseName
            if (name != null) facts.add(Fact("Name", TC.cap(name, 200), "Launcher"))
            for (k in listOf("GenericName", "Comment", "Type", "Exec", "TryExec", "Path", "URL", "Icon", "Terminal", "Categories", "MimeType", "NoDisplay", "Hidden", "StartupWMClass")) {
                sec[k]?.let { facts.add(Fact(k, TC.cap(it, 500), if (k == "Icon") "Appearance" else "Launcher")) }
            }
            val actions = ini(text).keys.count { it.startsWith("Desktop Action ") }
            if (actions > 0) facts.add(Fact("Actions", actions.toString(), "Launcher"))
            if (sec["Type"] == null) warnings.add("Required key Type is missing")
            return InspectionResult("desktop", "Desktop entry", name, facts, warnings)
        }
        if (text.contains("[InternetShortcut]", ignoreCase = true)) {
            val all = ini(text)
            val sec = all.entries.firstOrNull { it.key.equals("InternetShortcut", true) }?.value ?: return null
            baseName?.let { facts.add(Fact("Name", it, "Shortcut")) }
            val url = sec["URL"]
            if (url != null) facts.add(Fact("Target", TC.cap(url, 1000), "Shortcut")) else warnings.add("No URL key")
            for (k in listOf("IconFile", "IconIndex", "WorkingDirectory", "HotKey", "ShowCommand")) sec[k]?.let { facts.add(Fact(k, TC.cap(it, 300), "Shortcut")) }
            return InspectionResult("url", "Internet shortcut", url, facts, warnings)
        }
        if (text.contains("<plist") && text.contains("<key>URL</key>")) {
            val url = weblocUrl(text)?.trim()?.let { unxml(it) }
            baseName?.let { facts.add(Fact("Name", it, "Shortcut")) }
            if (url != null) facts.add(Fact("Target", TC.cap(url, 1000), "Shortcut"))
            return InspectionResult("webloc", "Web location", url, facts, warnings)
        }
        return null
    }
}
