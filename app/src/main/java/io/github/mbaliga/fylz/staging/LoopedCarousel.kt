package io.github.mbaliga.fylz.staging

/**
 * Index math for the expanded bulge's endless browse.
 *
 * The expanded tray scrolls "infinitely": flicking past the last item arrives back at the
 * first, with no edge and no jump. The cheap, robust way to get that in a `LazyRow` is a huge
 * virtual item count with the real item recovered by modulo — these functions are that mapping,
 * kept pure so the wrap-around arithmetic (the classic negative-modulo bug included) is pinned
 * by JVM tests rather than discovered by flicking left on a device.
 */
object LoopedCarousel {

    /**
     * Virtual item count for [itemCount] real items. Zero when the tray is empty — a looped
     * list of nothing must render nothing, not divide by zero. One real item still loops (the
     * reference behavior: browsing never hits a wall), which also means the virtual count must
     * stay comfortably clear of Int overflow while feeling endless in both directions.
     */
    fun virtualCount(itemCount: Int): Int = if (itemCount <= 0) 0 else VIRTUAL_SPAN

    /** The real item behind a virtual slot. Total function: any slot, any positive count. */
    fun itemIndex(virtualIndex: Int, itemCount: Int): Int {
        require(itemCount > 0) { "No items to map." }
        return Math.floorMod(virtualIndex, itemCount)
    }

    /**
     * The virtual slot to open the carousel on: the middle slot that lands on real item 0, so
     * the user can flick both ways ~a million times before meeting either virtual edge —
     * "infinite" by exhaustion rather than by cleverness.
     */
    fun startIndex(itemCount: Int): Int {
        if (itemCount <= 0) return 0
        val middle = VIRTUAL_SPAN / 2
        return middle - Math.floorMod(middle, itemCount)
    }

    private const val VIRTUAL_SPAN = 2_000_000
}
