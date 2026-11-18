package io.github.mbaliga.fylz.ui.places

/**
 * Geometry, colours and motion of the layered folder icon, taken from the design handoff
 * (`tokens.json`). Pure numbers, no Compose, so the poses and the press-to-fan deltas are
 * asserted in a test instead of eyeballed.
 *
 * The icon is drawn in a 139 x 124 unit space: a back plate, up to three "recent file" cards
 * that sit on it, then a frosted front plate with a tab notch over the lower part. Cards rise
 * out of the front plate when the folder is pressed.
 */
object FolderIconSpec {

    const val CANVAS_W = 139f
    const val CANVAS_H = 124f

    /** Front plate, in its own local space; it is placed [FRONT_Y] units down the canvas. */
    const val FRONT_W = 139f
    const val FRONT_H = 105f
    const val FRONT_Y = 19.0029f

    const val BACK_X = 9f
    const val BACK_Y = 11.002f
    const val BACK_W = 122f
    const val BACK_H = 113f
    const val BACK_RADIUS = 10f

    const val CARD_W = 74.7157f
    const val CARD_H = 83.0174f

    /** The photo is drawn 83.0174 square, shifted left, and clipped to the card's width. */
    const val PHOTO_SIZE = 83.0174f
    const val PHOTO_X = -4.151f

    /** Gaussian blur of the cards seen through the front plate, as a standard deviation in units. */
    const val BLUR_STD_DEV = 2f

    const val HOVER_MS = 380

    /** The front plate outline (tab notch at top-left), SVG path data in front-local units. */
    const val FRONT_PATH =
        "M0.819788 10.7597C0.377473 4.9543 4.96868 0 10.7909 0H52.5597C55.9518 0 59.1127 1.71958 " +
            "60.9554 4.5675L66.0446 12.4325C67.8873 15.2804 71.0482 17 74.4403 17H128.295C134.086 17 " +
            "138.666 21.903 138.272 27.6802L133.635 95.68C133.278 100.927 128.917 105 123.659 105H17.2671" +
            "C12.0389 105 7.69316 100.973 7.29598 95.76L0.819788 10.7597Z"

    /** Where a card sits: its top-left corner in canvas units and its rotation about that corner. */
    data class Pose(val x: Float, val y: Float, val rotationDeg: Float)

    /** The three card slots. A is the newest file and sits on top. */
    enum class Slot { A, B, C }

    /** Change in pose when the folder is pressed. */
    data class Delta(val dx: Float, val dy: Float, val dRot: Float)

    /** Colours for one theme; ARGB longs so this file stays free of Compose types. */
    data class Theme(
        val back: Long,
        val front: Long,
        val frontAlpha: Float,
        val stroke: Long,
        val line: Long,
        val lineShadow: Long,
        val lineShadowAlpha: Float,
        /** Where card A rests; B and C are offset from it. The two themes place it differently. */
        val cardOrigin: Pair<Float, Float>,
    )

    val LIGHT = Theme(0xFFD6DADB, 0xFFFFFFFF, 0.85f, 0xFFDEDFE1, 0xFFFFFFFF, 0xFFD9D9D9, 0.5f, 28f to 19.3379f)
    val DARK = Theme(0xFF2C2C2C, 0xFF000000, 0.80f, 0xFF242424, 0xFF040404, 0xFF4D4D4D, 0.3f, 4f to 33.3379f)

    fun theme(dark: Boolean): Theme = if (dark) DARK else LIGHT

    /** Cards shown for a folder with [fileCount] recent files: none, then one to three. */
    fun cardCount(fileCount: Int): Int = fileCount.coerceIn(0, 3)

    /** Slots in draw order, bottom to top, for [count] cards: C, B, A trimmed from the bottom. */
    fun slots(count: Int): List<Slot> = listOf(Slot.C, Slot.B, Slot.A).takeLast(count.coerceIn(0, 3))

    private fun restPose(slot: Slot, cardCount: Int, dark: Boolean): Pose {
        // The handoff only draws a dark folder with one card. Offsetting a second and third card
        // from that lower origin pushes them past the bottom of the plate, so a fan in the dark
        // theme uses the light origin, which the handoff does draw with three.
        val (ox, oy) = (if (dark && cardCount <= 1) DARK else LIGHT).cardOrigin
        return when (slot) {
            Slot.A -> Pose(ox, oy, -15f)
            Slot.B -> Pose(ox + 9f, oy + 2f, -3f)
            Slot.C -> Pose(ox + 46f, oy + 4f, 10f)
        }
    }

    /** Press deltas. A lone card moves a little less than when it is one of a fan. */
    fun delta(slot: Slot, cardCount: Int): Delta = if (cardCount <= 1) {
        Delta(-3f, -10f, -3f)
    } else when (slot) {
        Slot.A -> Delta(-6f, -12f, -6f)
        Slot.B -> Delta(2f, -8f, 6f)
        Slot.C -> Delta(8f, -6f, 8f)
    }

    /** The pose of [slot] at animation progress [t] (0 at rest, 1 fully fanned). */
    fun pose(slot: Slot, cardCount: Int, dark: Boolean, t: Float): Pose {
        val rest = restPose(slot, cardCount, dark)
        val d = delta(slot, cardCount)
        val k = t.coerceIn(0f, 1.2f) // a spring may overshoot a little
        return Pose(rest.x + d.dx * k, rest.y + d.dy * k, rest.rotationDeg + d.dRot * k)
    }
}
