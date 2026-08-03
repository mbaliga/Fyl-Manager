package io.github.mbaliga.fylz.preview

import kotlin.math.max
import kotlin.math.min

data class Point3(val x: Float, val y: Float, val z: Float)
data class Edge3(val start: Int, val end: Int)
data class Point2(val x: Float, val y: Float)
data class Segment2(val start: Point2, val end: Point2)
data class Circle2(val center: Point2, val radius: Float)
data class Arc2(val center: Point2, val radius: Float, val startDegrees: Float, val endDegrees: Float)

data class Bounds3(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    val center: Point3 get() = Point3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
    val span: Float get() = max(max(maxX - minX, maxY - minY), maxZ - minZ).coerceAtLeast(0.0001f)
}

data class MeshPreviewData(
    val vertices: List<Point3>,
    val edges: List<Edge3>,
    val faceCount: Int,
    val formatLabel: String,
    val truncated: Boolean,
) {
    val bounds: Bounds3 by lazy {
        if (vertices.isEmpty()) return@lazy Bounds3(0f, 0f, 0f, 1f, 1f, 1f)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        vertices.forEach {
            minX = min(minX, it.x); minY = min(minY, it.y); minZ = min(minZ, it.z)
            maxX = max(maxX, it.x); maxY = max(maxY, it.y); maxZ = max(maxZ, it.z)
        }
        Bounds3(minX, minY, minZ, maxX, maxY, maxZ)
    }
}

data class DrawingPreviewData(
    val segments: List<Segment2>,
    val circles: List<Circle2>,
    val arcs: List<Arc2>,
    val labels: List<Pair<Point2, String>>,
    val formatLabel: String,
    val truncated: Boolean,
) {
    fun points(): Sequence<Point2> = sequence {
        segments.forEach { yield(it.start); yield(it.end) }
        circles.forEach {
            yield(Point2(it.center.x - it.radius, it.center.y - it.radius))
            yield(Point2(it.center.x + it.radius, it.center.y + it.radius))
        }
        arcs.forEach {
            yield(Point2(it.center.x - it.radius, it.center.y - it.radius))
            yield(Point2(it.center.x + it.radius, it.center.y + it.radius))
        }
        labels.forEach { yield(it.first) }
    }
}
