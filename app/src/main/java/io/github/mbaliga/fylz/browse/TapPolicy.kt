package io.github.mbaliga.fylz.browse

/** What the user did to a row or cell. */
enum class ItemGesture { TAP, LONG_PRESS }

/** What the browser does about it. */
enum class ItemAction {
    /** Open the folder, or open the file in the app that handles it. */
    OPEN,

    /** The quick-preview card, with an action to add the file to the selection. */
    QUICK_PREVIEW,

    /** The quick-preview card, and the file is selected too. */
    PREVIEW_AND_SELECT,

    /** Flip this item in or out of the selection. */
    TOGGLE_SELECT,

    /** Start selecting, beginning with this item. */
    BEGIN_SELECTION,
}

/**
 * What a tap and a long press do, in one place so the rules can be asserted instead of being
 * scattered across the list and grid items.
 *
 * - While selecting, every tap toggles the item and a long press peeks at it.
 * - Otherwise a tap opens it and a long press is the quick preview (which offers "add to
 *   selection"). The "tap to preview" setting swaps the two roles; then a long press previews
 *   *and* selects, so a selection can still be started from a file.
 * - A folder has nothing to preview: tapping it always opens it, and a long press starts
 *   selecting with it.
 */
object TapPolicy {

    fun actionFor(
        gesture: ItemGesture,
        selecting: Boolean,
        tapToPreview: Boolean,
        isDirectory: Boolean,
    ): ItemAction = when {
        selecting -> when (gesture) {
            ItemGesture.TAP -> ItemAction.TOGGLE_SELECT
            ItemGesture.LONG_PRESS -> if (isDirectory) ItemAction.TOGGLE_SELECT else ItemAction.QUICK_PREVIEW
        }
        isDirectory -> when (gesture) {
            ItemGesture.TAP -> ItemAction.OPEN
            ItemGesture.LONG_PRESS -> ItemAction.BEGIN_SELECTION
        }
        tapToPreview -> when (gesture) {
            ItemGesture.TAP -> ItemAction.QUICK_PREVIEW
            ItemGesture.LONG_PRESS -> ItemAction.PREVIEW_AND_SELECT
        }
        else -> when (gesture) {
            ItemGesture.TAP -> ItemAction.OPEN
            ItemGesture.LONG_PRESS -> ItemAction.QUICK_PREVIEW
        }
    }
}
