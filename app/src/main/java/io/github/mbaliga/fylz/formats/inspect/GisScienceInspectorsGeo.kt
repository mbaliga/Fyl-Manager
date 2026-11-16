package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun regexCount(r: Regex, s: String): Int {
    var n = 0
    for (m in r.findAll(s)) { n++; if (n >= 50_000_000) break }
    return n
}

/** GPX 1.0/1.1 text scan: counts, bounds, time range, creator. */
object GisScienceGpxInspector : FormatInspector {
    override val formatIds = setOf("gpx")
    private val rWpt = Regex("<wpt[\\s>/]")
    private val rTrk = Regex("<trk[\\s>]")
    private val rSeg = Regex("<trkseg[\\s>/]")
    private val rTrkpt = Regex("<trkpt[\\s>/]")
    private val rRte = Regex("<rte[\\s>]")
    private val rRtept = Regex("<rtept[\\s>/]")
    private val rPtStart = Regex("<(?:wpt|trkpt|rtept)\\s")

    /** Linear-time inner text of the first <tag>...</tag> in [s]. */
    private fun inner(s: String, tag: String): String? {
        val a = s.indexOf("<$tag>"); if (a < 0) return null
        val b = s.indexOf("</$tag>", a + tag.length + 2); if (b < 0) return null
        return s.substring(a + tag.length + 2, b)
    }

    private fun parseInstant(t: String): Long? = try {
        java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
    } catch (e: Exception) { try { java.time.LocalDateTime.parse(t).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() } catch (e2: Exception) { null } }
    private val rEle = Regex("<ele>\\s*([-+0-9.eE]+)\\s*</ele>")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val raw = rd.head(GsKit.TEXT_READ)
        val text = GsKit.decodeText(raw)
        val gi = text.indexOf("<gpx")
        if (gi < 0 || gi > 4096) return@guard null
        val gend = text.indexOf('>', gi)
        val gtag = if (gend > 0) text.substring(gi, gend) else text.substring(gi, minOf(text.length, gi + 2000))
        val f = GsFacts()
        val truncated = source.size > raw.size
        if (truncated) f.warn("File is larger than the ${GsKit.mib(raw.size)} analysed; counts are lower bounds")
        f.add("Header", "GPX version", GsKit.attr(gtag, "version"))
        f.add("Header", "Creator", GsKit.attr(gtag, "creator")?.let { GsKit.unescape(it) })
        var md: String? = null
        run {
            var from = 0
            while (true) {
                val a = text.indexOf("<metadata", from); if (a < 0) break
                val c = text.getOrNull(a + 9)
                if (c != null && (c == '>' || c.isWhitespace())) {
                    val b = text.indexOf("</metadata>", a)
                    if (b > 0) md = text.substring(a, b)
                    break
                }
                from = a + 9
            }
        }
        if (md != null) {
            inner(md!!, "name")?.let { f.add("Header", "Name", GsKit.clip(GsKit.unescape(it), 200)) }
            inner(md!!, "desc")?.let { f.add("Header", "Description", GsKit.clip(GsKit.unescape(it), 300)) }
        }
        val wpt = regexCount(rWpt, text); val trk = regexCount(rTrk, text); val seg = regexCount(rSeg, text)
        val trkpt = regexCount(rTrkpt, text); val rte = regexCount(rRte, text); val rtept = regexCount(rRtept, text)
        f.add("Counts", "Waypoints", wpt)
        f.add("Counts", "Tracks", trk)
        f.add("Counts", "Track segments", seg)
        f.add("Counts", "Track points", trkpt)
        f.add("Counts", "Routes", rte)
        f.add("Counts", "Route points", rtept)
        var minLat = 91.0; var maxLat = -91.0; var minLon = 181.0; var maxLon = -181.0
        var pts = 0; var bad = 0
        var gtPos = -1
        for (m in rPtStart.findAll(text)) {
            val st = m.range.last + 1
            if (gtPos < st) { gtPos = text.indexOf('>', st); if (gtPos < 0) { bad++; break } }
            val en = gtPos
            if (en - st > 4096) { bad++; continue }
            val tag = text.substring(st, en)
            val lat = GsKit.attr(" $tag", "lat")?.toDoubleOrNull()
            val lon = GsKit.attr(" $tag", "lon")?.toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) { bad++; continue }
            pts++
            if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
        }
        if (pts > 0) {
            f.add("Bounds", "Min latitude", GsKit.num(minLat)); f.add("Bounds", "Max latitude", GsKit.num(maxLat))
            f.add("Bounds", "Min longitude", GsKit.num(minLon)); f.add("Bounds", "Max longitude", GsKit.num(maxLon))
        }
        if (bad > 0) f.warn("$bad point(s) with missing or out-of-range lat/lon were ignored")
        var tMin: String? = null; var tMax: String? = null; var tn = 0; var tMinP: String? = null; var tMaxP: String? = null
        var mMin = Long.MAX_VALUE; var mMax = Long.MIN_VALUE; var allParsed = true
        run {
            var from = 0
            while (true) {
                val a = text.indexOf("<time>", from); if (a < 0) break
                val lt = text.indexOf('<', a + 6); if (lt < 0) break
                if (!text.startsWith("</time>", lt)) { from = a + 6; continue }
                val t = text.substring(a + 6, lt).trim(); from = lt
                if (t.isEmpty()) continue
                tn++
                if (tMin == null || t < tMin!!) tMin = t
                if (tMax == null || t > tMax!!) tMax = t
                val ms = parseInstant(t)
                if (ms == null) allParsed = false else { if (ms < mMin) { mMin = ms; tMinP = t }; if (ms > mMax) { mMax = ms; tMaxP = t } }
            }
        }
        if (allParsed && tn > 0) { tMin = tMinP; tMax = tMaxP }
        if (tn > 0) { f.add("Time", "Earliest time", tMin); f.add("Time", "Latest time", tMax) }
        var eMin = Double.MAX_VALUE; var eMax = -Double.MAX_VALUE; var en = 0
        for (m in rEle.findAll(text)) {
            val e = m.groupValues[1].toDoubleOrNull() ?: continue
            en++; if (e < eMin) eMin = e; if (e > eMax) eMax = e
        }
        if (en > 0) { f.add("Elevation", "Min elevation (m)", GsKit.num(eMin)); f.add("Elevation", "Max elevation (m)", GsKit.num(eMax)) }
        // track names
        var shown = 0
        for (m in rTrk.findAll(text)) {
            if (shown >= 10) break
            val s = m.range.first
            var e = text.length
            for (stop in listOf("<trkseg", "<trkpt", "</trk>")) { val i = text.indexOf(stop, s); if (i in 0 until e) e = i }
            val ni = text.indexOf("<name>", s)
            if (ni in 0 until e) {
                val ne = text.indexOf("</name>", ni)
                if (ne > ni && ne - ni < 500) { f.add("Tracks", "Track name", GsKit.unescape(text.substring(ni + 6, ne))); shown++ }
            }
        }
        if (wpt + trk + rte == 0 && gend < 0) f.warn("No complete <gpx> root tag found")
        InspectionResult("gpx", "GPX GPS data",
            "$wpt waypoints, $trk tracks, $rte routes", f.facts, f.warnings)
    }
}

/** KML text scan. */
object GisScienceKmlInspector : FormatInspector {
    override val formatIds = setOf("kml")
    private fun re(tag: String) = Regex("<$tag[\\s>/]")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val raw = rd.head(GsKit.TEXT_READ)
        val text = GsKit.decodeText(raw)
        val ki = text.indexOf("<kml")
        if (ki < 0 || ki > 4096) return@guard null
        val f = GsFacts()
        if (source.size > raw.size) f.warn("File is larger than the ${GsKit.mib(raw.size)} analysed; counts are lower bounds")
        val kend = text.indexOf('>', ki)
        if (kend > 0) GsKit.attr(text.substring(ki, kend), "xmlns")?.let { f.add("Header", "Namespace", it) }
        val di = Regex("<Document[\\s>]").find(text)?.range?.first ?: -1
        if (di >= 0) {
            var lim = text.length
            for (s in listOf("<Placemark", "<Folder", "<Style", "<StyleMap", "<Schema")) { val i = text.indexOf(s, di); if (i in 0 until lim) lim = i }
            val ni = text.indexOf("<name>", di)
            if (ni in 0 until lim) {
                val ne = text.indexOf("</name>", ni)
                if (ne > ni) f.add("Document", "Document name", GsKit.clip(GsKit.unescape(text.substring(ni + 6, ne)), 200))
            }
            val dsi = text.indexOf("<description>", di)
            if (dsi in 0 until lim) {
                val dse = text.indexOf("</description>", dsi)
                if (dse > dsi) f.add("Document", "Description", GsKit.clip(GsKit.unescape(text.substring(dsi + 13, dse)), 300))
            }
        }
        val pm = regexCount(re("Placemark"), text)
        f.add("Counts", "Placemarks", pm)
        f.add("Counts", "Folders", regexCount(re("Folder"), text))
        f.add("Geometry", "Points", regexCount(re("Point"), text))
        f.add("Geometry", "LineStrings", regexCount(re("LineString"), text))
        f.add("Geometry", "Polygons", regexCount(re("Polygon"), text))
        f.add("Geometry", "MultiGeometries", regexCount(re("MultiGeometry"), text))
        f.add("Geometry", "gx:Tracks", regexCount(Regex("<gx:Track[\\s>/]"), text))
        f.add("Other", "Ground overlays", regexCount(re("GroundOverlay"), text))
        f.add("Other", "Screen overlays", regexCount(re("ScreenOverlay"), text))
        f.add("Other", "Network links", regexCount(re("NetworkLink"), text))
        f.add("Other", "Styles", regexCount(re("Style"), text))
        // drop zero-count facts to stay readable
        val kept = f.facts.filter { !(it.group in setOf("Geometry", "Other") && it.value == "0") }
        InspectionResult("kml", "KML geographic data", "$pm placemarks", kept, f.warnings)
    }
}

/** GeoJSON via org.json, bounded by size and nesting depth. */
object GisScienceGeoJsonInspector : FormatInspector {
    override val formatIds = setOf("geojson")
    private const val MAX_PARSE = 4 * 1024 * 1024
    private val TYPES = setOf("Feature", "FeatureCollection", "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon", "GeometryCollection")

    private class Acc {
        val geomTypes = java.util.TreeMap<String, Int>()
        var positions = 0L
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        val propKeys = java.util.TreeSet<String>()
        var nullGeoms = 0
    }

    private fun maxDepth(b: ByteArray): Int {
        var d = 0; var m = 0; var inStr = false; var esc = false
        for (x in b) {
            val c = x.toInt()
            if (inStr) { if (esc) esc = false else if (c == '\\'.code) esc = true else if (c == '"'.code) inStr = false }
            else if (c == '"'.code) inStr = true
            else if (c == '['.code || c == '{'.code) { d++; if (d > m) m = d }
            else if (c == ']'.code || c == '}'.code) d--
        }
        return m
    }

    private fun walkCoords(a: org.json.JSONArray, depth: Int, acc: Acc) {
        if (depth > 8 || acc.positions > 3_000_000) return
        val first = a.opt(0)
        if (first is Number) {
            if (a.length() >= 2) {
                val x = a.optDouble(0, Double.NaN); val y = a.optDouble(1, Double.NaN)
                if (!x.isNaN() && !y.isNaN()) {
                    acc.positions++
                    if (x < acc.minX) acc.minX = x; if (x > acc.maxX) acc.maxX = x
                    if (y < acc.minY) acc.minY = y; if (y > acc.maxY) acc.maxY = y
                }
            }
            return
        }
        for (i in 0 until minOf(a.length(), 2_000_000)) {
            val e = a.opt(i)
            if (e is org.json.JSONArray) walkCoords(e, depth + 1, acc)
            if (acc.positions > 3_000_000) return
        }
    }

    private fun walkGeom(g: org.json.JSONObject?, depth: Int, acc: Acc) {
        if (g == null) { acc.nullGeoms++; return }
        val t = g.optString("type", "?")
        acc.geomTypes[t] = (acc.geomTypes[t] ?: 0) + 1
        if (t == "GeometryCollection") {
            val gs = g.optJSONArray("geometries") ?: return
            if (depth > 6) return
            for (i in 0 until minOf(gs.length(), 100_000)) walkGeom(gs.optJSONObject(i), depth + 1, acc)
        } else {
            g.optJSONArray("coordinates")?.let { walkCoords(it, 0, acc) }
        }
    }

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val raw = rd.head(MAX_PARSE)
        val f = GsFacts()
        val text = GsKit.decodeText(raw)
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return@guard null
        val partial = source.size > raw.size
        val obj: org.json.JSONObject
        if (partial) {
            val t = Regex("\"type\"\\s*:\\s*\"(\\w+)\"").find(text)?.groupValues?.get(1)
            if (t == null || t !in TYPES) return@guard null
            f.warn("File is larger than the ${GsKit.mib(raw.size)} parse limit; only the type was read")
            f.add("Header", "Type", t)
            return@guard InspectionResult("geojson", "GeoJSON", t, f.facts, f.warnings)
        }
        if (maxDepth(raw) > 200) {
            f.warn("Nesting deeper than 200 levels; not parsed")
            return@guard InspectionResult("geojson", "GeoJSON", null, f.facts, f.warnings)
        }
        obj = try { org.json.JSONObject(trimmed) } catch (t: Throwable) { return@guard null }
        val type = obj.optString("type", "")
        if (type !in TYPES) return@guard null
        val acc = Acc()
        f.add("Header", "Type", type)
        var summary = type
        when (type) {
            "FeatureCollection" -> {
                val fs = obj.optJSONArray("features")
                val n = fs?.length() ?: 0
                f.add("Content", "Feature count", n)
                summary = "FeatureCollection, $n features"
                if (fs != null) for (i in 0 until minOf(n, 500_000)) {
                    val ft = fs.optJSONObject(i) ?: continue
                    walkGeom(ft.optJSONObject("geometry"), 0, acc)
                    if (acc.propKeys.size < 40) ft.optJSONObject("properties")?.keys()?.let { it.asSequence().take(60).forEach { k -> if (acc.propKeys.size < 40) acc.propKeys.add(k) } }
                }
                if (n > 500_000) f.warn("Only the first 500000 features were examined")
            }
            "Feature" -> {
                walkGeom(obj.optJSONObject("geometry"), 0, acc)
                obj.optJSONObject("properties")?.keys()?.let { it.asSequence().take(40).forEach { k -> acc.propKeys.add(k) } }
            }
            else -> walkGeom(obj, 0, acc)
        }
        if (acc.geomTypes.isNotEmpty()) f.add("Content", "Geometry types", acc.geomTypes.entries.joinToString(", ") { "${it.key} x${it.value}" })
        if (acc.nullGeoms > 0) f.add("Content", "Features without geometry", acc.nullGeoms)
        if (acc.positions > 0) {
            f.add("Content", "Coordinate positions", acc.positions)
            f.add("Bounds", "Computed min x (lon)", GsKit.num(acc.minX)); f.add("Bounds", "Computed min y (lat)", GsKit.num(acc.minY))
            f.add("Bounds", "Computed max x (lon)", GsKit.num(acc.maxX)); f.add("Bounds", "Computed max y (lat)", GsKit.num(acc.maxY))
        }
        obj.optJSONArray("bbox")?.let { bb ->
            val vals = (0 until minOf(bb.length(), 8)).map { bb.opt(it) }
            if (vals.all { it is Number }) f.add("Bounds", "Declared bbox", vals.joinToString(", ") { GsKit.num((it as Number).toDouble()) })
        }
        obj.optJSONObject("crs")?.optJSONObject("properties")?.optString("name", "")?.let { if (it.isNotEmpty()) f.add("Header", "CRS", it) }
        if (acc.propKeys.isNotEmpty()) f.add("Properties", "Property keys (sample)", acc.propKeys.joinToString(", "))
        InspectionResult("geojson", "GeoJSON", summary, f.facts, f.warnings)
    }
}

/** ESRI Shapefile main file (.shp). */
object GisScienceShpInspector : FormatInspector {
    override val formatIds = setOf("shp")

    private val NAMES = mapOf(0 to "Null shape", 1 to "Point", 3 to "PolyLine", 5 to "Polygon", 8 to "MultiPoint",
        11 to "PointZ", 13 to "PolyLineZ", 15 to "PolygonZ", 18 to "MultiPointZ",
        21 to "PointM", 23 to "PolyLineM", 25 to "PolygonM", 28 to "MultiPointM", 31 to "MultiPatch")

    override fun inspect(source: ByteSource, fileName: String?): InspectionResult? = GsKit.guard {
        val rd = GsReader(source)
        val h = rd.read(0, 100)
        if (h.size < 100) return@guard null
        if (Bytes.u32be(h, 0) != 9994L) return@guard null
        val f = GsFacts()
        val version = Bytes.u32le(h, 28)
        val type = Bytes.u32le(h, 32)
        val lenWords = Bytes.u32be(h, 24)
        val declared = lenWords * 2
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        f.add("Header", "File code", 9994)
        f.add("Header", "Version", version)
        val tn = NAMES[type.toInt()]
        f.add("Header", "Shape type", if (tn != null) "$tn ($type)" else "unknown ($type)")
        f.add("Header", "Declared file length", "$declared bytes")
        if (version != 1000L) f.warn("Unexpected version $version (expected 1000)")
        if (tn == null) f.warn("Unknown shape type $type")
        if (declared != source.size) f.warn("Declared length $declared differs from actual size ${source.size}")
        fun d(o: Int) = bb.getDouble(o)
        val xmin = d(36); val ymin = d(44); val xmax = d(52); val ymax = d(60)
        if (listOf(xmin, ymin, xmax, ymax).all { !it.isNaN() && !it.isInfinite() }) {
            f.add("Bounds", "X min", GsKit.num(xmin)); f.add("Bounds", "Y min", GsKit.num(ymin))
            f.add("Bounds", "X max", GsKit.num(xmax)); f.add("Bounds", "Y max", GsKit.num(ymax))
        } else f.warn("Bounding box contains non-finite values")
        if (type.toInt() in setOf(11, 13, 15, 18, 31)) {
            f.add("Bounds", "Z min", GsKit.num(d(68))); f.add("Bounds", "Z max", GsKit.num(d(76)))
        }
        if (type.toInt() in setOf(11, 13, 15, 18, 21, 23, 25, 28, 31)) {
            val mmin = d(84); val mmax = d(92)
            if (mmin > -1e38 && mmax > -1e38) { f.add("Bounds", "M min", GsKit.num(mmin)); f.add("Bounds", "M max", GsKit.num(mmax)) }
        }
        // count records within a bounded window
        var off = 100L; var count = 0L; var complete = false
        val limit = minOf(source.size, declared.coerceAtLeast(100))
        val block = 1 shl 20
        var buf = rd.read(off, block); var bufStart = off
        while (count < 5_000_000) {
            if (off + 8 > limit) { complete = off >= limit; break }
            var rel = (off - bufStart).toInt()
            if (rel + 8 > buf.size) {
                buf = rd.read(off, block); bufStart = off; rel = 0
                if (buf.size < 8) break
            }
            val contentWords = Bytes.u32be(buf, rel + 4)
            val next = off + 8 + contentWords * 2
            if (next > limit) { f.warn("Record ${count + 1} extends past the end of the file"); break }
            count++; off = next
            if (off == limit) { complete = true; break }
        }
        f.add("Records", if (complete) "Record count" else "Records counted (at least)", count)
        InspectionResult("shp", "ESRI Shapefile", "${tn ?: "unknown type"}, $count records", f.facts, f.warnings)
    }
}
