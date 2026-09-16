package io.github.mbaliga.fylz.data

import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A point in an [EdgeCostMap]'s own pixel space. */
data class PixelPoint(val x: Int, val y: Int)

/**
 * The magnetic lasso's pathfinding half: [snap] finds the cheapest route between two points
 * through an [EdgeCostMap], preferring real edges over the straight line a plain lasso would draw.
 * [EdgeCostMap.from] builds the map this searches; [SelectionMask.fromPath] rasterizes the
 * resulting points into a selection exactly the way a plain lasso's own drag does -- magnetic
 * lasso only changes how the points between two anchors are chosen, not what happens after.
 */
object MagneticPath {

    private const val DIAGONAL_DISTANCE = 1.4142135f // sqrt(2), the cost of a diagonal step vs. 1 for orthogonal.

    /**
     * Dijkstra restricted to a window around [from] and [to], expanded by [margin] pixels in
     * every direction -- a full-image search recomputed for every placed anchor would not scale
     * to a real photo, and a route between two nearby anchors has no reason to wander far outside
     * their own neighborhood anyway.
     *
     * Priority queue entries carry their own distance at insertion time rather than comparing
     * through a live, mutating distance array: `PriorityQueue`'s heap invariant assumes a stable
     * ordering once an element is inserted, and re-ranking entries by mutating what the
     * comparator reads out from under it (instead of pushing a fresh entry) would silently break
     * that invariant -- `poll()` could stop returning the true minimum with no exception to catch
     * it. Stale duplicate entries (a node queued more than once as cheaper paths are found) are
     * the accepted cost of that safety, filtered out on pop via the `settled` check.
     */
    fun snap(costMap: EdgeCostMap, from: PixelPoint, to: PixelPoint, margin: Int = 40): List<PixelPoint> {
        val left = max(0, min(from.x, to.x) - margin)
        val top = max(0, min(from.y, to.y) - margin)
        val right = min(costMap.width - 1, max(from.x, to.x) + margin)
        val bottom = min(costMap.height - 1, max(from.y, to.y) + margin)
        val windowWidth = right - left + 1
        val size = windowWidth * (bottom - top + 1)

        fun indexOf(x: Int, y: Int) = (y - top) * windowWidth + (x - left)

        val distance = FloatArray(size) { Float.MAX_VALUE }
        val settled = BooleanArray(size)
        val previous = IntArray(size) { -1 }

        val startIndex = indexOf(from.x, from.y)
        val targetIndex = indexOf(to.x, to.y)
        distance[startIndex] = 0f

        val queue = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        queue.add(0f to startIndex)

        while (queue.isNotEmpty()) {
            val (pathDistance, current) = queue.poll()!!
            if (settled[current]) continue
            if (pathDistance > distance[current]) continue
            settled[current] = true
            if (current == targetIndex) break

            val cx = left + current % windowWidth
            val cy = top + current / windowWidth
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < left || nx > right || ny < top || ny > bottom) continue
                    val neighborIndex = indexOf(nx, ny)
                    if (settled[neighborIndex]) continue
                    val stepDistance = if (dx != 0 && dy != 0) DIAGONAL_DISTANCE else 1f
                    val candidate = distance[current] + costMap.costAt(nx, ny) * stepDistance
                    if (candidate < distance[neighborIndex]) {
                        distance[neighborIndex] = candidate
                        previous[neighborIndex] = current
                        queue.add(candidate to neighborIndex)
                    }
                }
            }
        }

        if (!settled[targetIndex]) return listOf(from, to)

        val path = mutableListOf<PixelPoint>()
        var step = targetIndex
        while (step != -1) {
            path.add(PixelPoint(left + step % windowWidth, top + step / windowWidth))
            step = previous[step]
        }
        return path.asReversed()
    }
}
