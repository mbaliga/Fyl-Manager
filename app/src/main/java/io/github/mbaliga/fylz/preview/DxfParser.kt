package io.github.mbaliga.fylz.preview

import java.util.Locale

object DxfParser {
    const val MAX_INPUT_BYTES = 24 * 1024 * 1024
    const val MAX_ENTITIES = 300_000
    const val MAX_LABELS = 10_000

    fun parse(bytes: ByteArray): DrawingPreviewData {
        require(bytes.size <= MAX_INPUT_BYTES) { "Drawing exceeds the in-app preview limit." }
        require(!bytes.take(22).toByteArray().toString(Charsets.US_ASCII).startsWith("AutoCAD Binary DXF")) {
            "Binary DXF is available through the universal inspector, not the vector renderer."
        }
        val raw = bytes.toString(Charsets.UTF_8).lineSequence().toList()
        val pairs = raw.chunked(2).mapNotNull { pair ->
            if (pair.size < 2) null else pair[0].trim().toIntOrNull()?.let { it to pair[1].trim() }
        }
        val segments = mutableListOf<Segment2>()
        val circles = mutableListOf<Circle2>()
        val arcs = mutableListOf<Arc2>()
        val labels = mutableListOf<Pair<Point2, String>>()
        var index = 0
        var inEntities = false
        var entities = 0
        var truncated = false

        while (index < pairs.size) {
            val (code, value) = pairs[index]
            if (code == 0 && value.equals("SECTION", true) && pairs.getOrNull(index + 1) == (2 to "ENTITIES")) {
                inEntities = true
                index += 2
                continue
            }
            if (inEntities && code == 0 && value.equals("ENDSEC", true)) break
            if (!inEntities || code != 0) { index += 1; continue }
            if (entities >= MAX_ENTITIES) { truncated = true; break }
            val next = (index + 1 until pairs.size).firstOrNull { pairs[it].first == 0 } ?: pairs.size
            val body = pairs.subList(index + 1, next)
            when (value.uppercase(Locale.ROOT)) {
                "LINE" -> parseLine(body)?.let(segments::add)
                "CIRCLE" -> parseCircle(body)?.let(circles::add)
                "ARC" -> parseArc(body)?.let(arcs::add)
                "LWPOLYLINE" -> segments += parseLightPolyline(body)
                "POINT" -> point(body, 10, 20)?.let { p ->
                    val epsilon = 0.5f
                    segments += Segment2(Point2(p.x - epsilon, p.y), Point2(p.x + epsilon, p.y))
                    segments += Segment2(Point2(p.x, p.y - epsilon), Point2(p.x, p.y + epsilon))
                }
                "TEXT", "MTEXT" -> if (labels.size < MAX_LABELS) {
                    val p = point(body, 10, 20)
                    val text = values(body, 1).joinToString("").ifBlank { value(body, 3).orEmpty() }
                    if (p != null && text.isNotBlank()) labels += p to text.take(512)
                }
            }
            entities += 1
            index = next
        }
        return DrawingPreviewData(
            segments = segments.take(MAX_ENTITIES * 8),
            circles = circles.take(MAX_ENTITIES),
            arcs = arcs.take(MAX_ENTITIES),
            labels = labels,
            formatLabel = "ASCII DXF",
            truncated = truncated || segments.size > MAX_ENTITIES * 8,
        )
    }

    private fun parseLine(body: List<Pair<Int, String>>): Segment2? {
        val start = point(body, 10, 20) ?: return null
        val end = point(body, 11, 21) ?: return null
        return Segment2(start, end)
    }

    private fun parseCircle(body: List<Pair<Int, String>>): Circle2? {
        val center = point(body, 10, 20) ?: return null
        val radius = value(body, 40)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return null
        return Circle2(center, radius)
    }

    private fun parseArc(body: List<Pair<Int, String>>): Arc2? {
        val center = point(body, 10, 20) ?: return null
        val radius = value(body, 40)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return null
        val start = value(body, 50)?.toFloatOrNull() ?: return null
        val end = value(body, 51)?.toFloatOrNull() ?: return null
        return Arc2(center, radius, start, end)
    }

    private fun parseLightPolyline(body: List<Pair<Int, String>>): List<Segment2> {
        val points = mutableListOf<Point2>()
        var pendingX: Float? = null
        body.forEach { (code, raw) ->
            when (code) {
                10 -> pendingX = raw.toFloatOrNull()?.takeIf(Float::isFinite)
                20 -> {
                    val x = pendingX
                    val y = raw.toFloatOrNull()?.takeIf(Float::isFinite)
                    if (x != null && y != null) points += Point2(x, y)
                    pendingX = null
                }
            }
        }
        val closed = value(body, 70)?.toIntOrNull()?.let { it and 1 == 1 } == true
        return buildList {
            points.zipWithNext().forEach { add(Segment2(it.first, it.second)) }
            if (closed && points.size > 2) add(Segment2(points.last(), points.first()))
        }
    }

    private fun point(body: List<Pair<Int, String>>, xCode: Int, yCode: Int): Point2? {
        val x = value(body, xCode)?.toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
        val y = value(body, yCode)?.toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
        return Point2(x, y)
    }

    private fun value(body: List<Pair<Int, String>>, code: Int): String? = body.firstOrNull { it.first == code }?.second
    private fun values(body: List<Pair<Int, String>>, code: Int): List<String> = body.filter { it.first == code }.map { it.second }
}
