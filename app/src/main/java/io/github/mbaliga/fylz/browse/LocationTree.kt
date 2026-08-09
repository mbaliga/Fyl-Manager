package io.github.mbaliga.fylz.browse

/** How many children of the current folder the tree shows before it starts hiding them. */
const val DEFAULT_TREE_CHILDREN = 8

/** A child of the current folder, reduced to what the tree needs to draw it. */
data class TreeChild(val name: String, val directory: Boolean)

/** What a tree row points at, so the caller can map a tap back to its own data. */
sealed interface TreeTarget {
    /** A step in the open location's folder stack, by index into it. */
    data class Ancestor(val index: Int) : TreeTarget

    /** A child of the current folder, by index into the child list handed in. */
    data class Child(val index: Int) : TreeTarget

    /** The tail row counting what did not fit. Not a destination. */
    data class Hidden(val count: Int) : TreeTarget
}

/**
 * One drawn line of the tree.
 *
 * [depth] is the indent level, and also exactly how many guide rules run down the left of the
 * row — the tree is a single spine, so those two numbers can never disagree.
 */
data class TreeRow(
    val label: String,
    val depth: Int,
    val directory: Boolean,
    val expanded: Boolean,
    val current: Boolean,
    val target: TreeTarget,
)

/**
 * The "where does this live" tree shown in the details room.
 *
 * A breadcrumb answers *what* the path is; it does not show that a folder has siblings under it
 * or where the thing you have selected sits among them. This builds the same shape a desktop
 * file tree has — the open path expanded all the way down, the current folder's own children
 * listed under it — which is what makes a nested location legible at a glance rather than a
 * string of names separated by slashes.
 *
 * Every ancestor is expanded because it is on the open path: there is no closed ancestor of a
 * folder you are standing in. Children are collapsed because nothing below them has been read.
 *
 * ### Why it is windowed
 *
 * A folder of two thousand files would otherwise turn a details surface into a second file
 * listing, which the browser already is and does better. Only [maxChildren] rows are drawn, and
 * the window is centred on the focused child so the selected file is always in it — scrolling a
 * details pane to find the thing you just tapped would be a poor kind of detail. What did not
 * fit is counted in a trailing [TreeTarget.Hidden] row rather than silently dropped.
 *
 * @param ancestors the open location's folder stack, root first, current folder last.
 * @param children the current folder's entries to offer under it, in display order.
 * @param focusedChild index into [children] of the entry the room is describing, or null when
 *   the subject is the current folder itself.
 */
fun locationTree(
    ancestors: List<String>,
    children: List<TreeChild> = emptyList(),
    focusedChild: Int? = null,
    maxChildren: Int = DEFAULT_TREE_CHILDREN,
): List<TreeRow> {
    if (ancestors.isEmpty()) return emptyList()
    val focused = focusedChild?.takeIf { it in children.indices }
    val rows = ArrayList<TreeRow>(ancestors.size + minOf(children.size, maxChildren) + 1)

    ancestors.forEachIndexed { index, name ->
        rows += TreeRow(
            label = name,
            depth = index,
            directory = true,
            expanded = true,
            // With nothing focused below it, the folder you are standing in is the current row.
            current = focused == null && index == ancestors.lastIndex,
            target = TreeTarget.Ancestor(index),
        )
    }
    if (children.isEmpty()) return rows

    val window = childWindow(children.size, focused, maxChildren)
    for (index in window) {
        rows += TreeRow(
            label = children[index].name,
            depth = ancestors.size,
            directory = children[index].directory,
            expanded = false,
            current = index == focused,
            target = TreeTarget.Child(index),
        )
    }
    val hidden = children.size - window.count()
    if (hidden > 0) {
        rows += TreeRow(
            label = if (hidden == 1) "1 more in this folder" else "$hidden more in this folder",
            depth = ancestors.size,
            directory = false,
            expanded = false,
            current = false,
            target = TreeTarget.Hidden(hidden),
        )
    }
    return rows
}

/**
 * Which slice of the children is drawn.
 *
 * Clamped rather than centred blindly: a focus near either end must not produce a window that
 * runs off the list and shows fewer rows than it could.
 */
internal fun childWindow(size: Int, focused: Int?, max: Int): IntRange {
    if (max <= 0 || size <= 0) return IntRange.EMPTY
    if (size <= max) return 0 until size
    val start = ((focused ?: 0) - max / 2).coerceIn(0, size - max)
    return start until start + max
}
