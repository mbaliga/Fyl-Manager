package io.github.mbaliga.fylz.ui.tree

import io.github.mbaliga.fylz.model.FolderLocation

/**
 * The maths behind the file tree room — the file pane shrinking into its own folder node, and
 * the tree resolving around it.
 *
 * This is Fotoz's `PlaceMorph` carried over: there the photo flies into its pin on the map; here
 * the file pane flies into its folder on the tree. Same shape for the same reason — the flight is
 * driven by a *drag*, so it has to be right at every fractional openness the finger can stop at.
 * Every function is pure so the feel is asserted in a test.
 */
object FolderMorph {

    /** The folder node's size once landed, in dp. */
    const val NODE_SIZE_DP = 44

    /** Corner radius of the node once landed. The pane starts at [PANE_CORNER_DP]. */
    const val NODE_CORNER_DP = 10

    /** Corner radius of the file pane at the start of the flight — the reference's card radius. */
    const val PANE_CORNER_DP = 28

    /** How far each tree row starts below its resting place, in dp, as it settles. */
    private const val ROW_SLIDE_DP = 18f

    private const val HERO_FRACTION = 0.92f

    private const val FLIGHT_START = 0f
    private const val FLIGHT_END = 0.85f
    private const val TREE_START = 0.15f
    private const val TREE_END = 1f
    private const val TEXT_START = 0.45f
    private const val TEXT_END = 1f

    /** Smoothstep over the shell's linear drag, so the pane departs and arrives rather than slams. */
    fun flight(reveal: Float): Float {
        val t = reveal.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Re-times a 0..1 [reveal] onto [start]..[end] so the parts of the room arrive in sequence. */
    fun stagger(reveal: Float, start: Float, end: Float): Float {
        if (end <= start) return if (reveal >= end) 1f else 0f
        return ((reveal - start) / (end - start)).coerceIn(0f, 1f)
    }

    /** The pane spans [HERO_FRACTION] of the room's width at the start and lands at the node's size. */
    fun heroScale(roomWidthDp: Float): Float =
        if (roomWidthDp <= 0f) 1f else ((roomWidthDp * HERO_FRACTION) / NODE_SIZE_DP).coerceAtLeast(1f)

    /** The pane's scale this frame: [heroScale]× at reveal 0, 1× (the node) when fully open. */
    fun paneScale(reveal: Float, heroScale: Float): Float {
        val t = flight(stagger(reveal, FLIGHT_START, FLIGHT_END))
        return heroScale + (1f - heroScale) * t
    }

    /** 0 while the node is still a pane (shows file rows), 1 once it is a folder (shows the glyph). */
    fun folderGlyphAlpha(reveal: Float): Float = flight(stagger(reveal, 0.55f, 0.95f))

    /** Corner radius this frame, easing from the card's to the node's. */
    fun cornerDp(reveal: Float): Float {
        val t = flight(stagger(reveal, FLIGHT_START, FLIGHT_END))
        return PANE_CORNER_DP + (NODE_CORNER_DP - PANE_CORNER_DP) * t
    }

    /** The rest of the tree resolves under the flying pane, a beat after it starts. */
    fun treeAlpha(reveal: Float): Float = flight(stagger(reveal, TREE_START, TREE_END))

    /** Row text arrives last, so the eye follows the pane first. */
    fun textAlpha(reveal: Float): Float = flight(stagger(reveal, TEXT_START, TEXT_END))

    /** Rows slide UP into place over the window they fade in over. */
    fun textOffsetDp(reveal: Float): Float = ROW_SLIDE_DP * (1f - textAlpha(reveal))
}

/** Where a row sits relative to the folder the file pane is showing. */
enum class TreeRowKind { ANCESTOR, CURRENT, CHILD }

/**
 * One row of the file tree. [locationIndex] is the index into the tab's `locations` for ancestors
 * and the current folder, so tapping navigates by truncating the trail; children carry their own
 * identity in [childKey] and open by entry.
 */
data class TreeRow(
    val kind: TreeRowKind,
    val depth: Int,
    val name: String,
    val locationIndex: Int? = null,
    val childKey: String? = null,
)

/** Builds the tree from the trail the tab already keeps: root → … → current, then its folders. */
object FileTreeModel {

    /** Cap on children shown, so a folder of thousands of subfolders cannot make the room a wall. */
    const val MAX_CHILDREN = 40

    fun rows(
        trail: List<FolderLocation>,
        childFolders: List<Pair<String, String>>,
    ): List<TreeRow> {
        if (trail.isEmpty()) return emptyList()
        val last = trail.lastIndex
        return buildList {
            trail.forEachIndexed { index, location ->
                add(
                    TreeRow(
                        kind = if (index == last) TreeRowKind.CURRENT else TreeRowKind.ANCESTOR,
                        depth = index,
                        name = location.name.ifBlank { "Folder" },
                        locationIndex = index,
                    ),
                )
            }
            childFolders.take(MAX_CHILDREN).forEach { (key, name) ->
                add(TreeRow(TreeRowKind.CHILD, depth = trail.size, name = name, childKey = key))
            }
        }
    }
}
