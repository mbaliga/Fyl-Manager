package io.github.mbaliga.fylz.workspace

/**
 * Keyboard modifiers held while a pointer (or touch) click lands. Tracked at the shell root by
 * resyncing from every hardware key event's own meta flags — self-healing, so a modifier
 * released while a dialog held focus cannot stick.
 */
data class HeldModifiers(
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    val any: Boolean get() = ctrl || shift || alt || meta

    companion object {
        val None = HeldModifiers()
    }
}

/** What a modifier-qualified click on a listing entry means. */
sealed interface ModifierClick {
    /** No qualifying modifier: the ordinary open. */
    data object Open : ModifierClick

    /** Ctrl/Meta+click (or Shift+click with no anchor yet): toggle this entry's selection. */
    data object ToggleSelection : ModifierClick

    /** Shift+click with an anchor: select the whole visible span, inclusive both ends. */
    data class SelectRange(val fromIndex: Int, val toIndex: Int) : ModifierClick {
        init {
            require(fromIndex <= toIndex)
        }
    }

    /** Alt+click: hand the entry to another app — the desktop's "open with" reflex. */
    data object OpenExternal : ModifierClick
}

/**
 * WP-A4's click half: the desktop file-manager modifier vocabulary, pure and testable.
 * Precedence mirrors every desktop file manager: Shift (range) outranks Ctrl (toggle), which
 * outranks Alt. Ctrl and Meta are interchangeable, same as `KeyboardShortcutPolicy`.
 *
 * @param anchorIndex the last plainly-clicked or toggled entry's position in the CURRENTLY
 *   visible list, or null when there is no anchor (nothing clicked yet, or the anchor left the
 *   listing). A Shift+click with no anchor degrades to a toggle, which also plants the anchor —
 *   the second Shift+click then gets its range.
 * @param clickedIndex the clicked entry's position in the same visible list, or -1 when the
 *   entry is not in it (a desktop shortcut, a recents tap): every modifier read degrades to
 *   [ModifierClick.Open] there, because a range or toggle against a list that does not contain
 *   the click is a guess.
 */
object ModifierClickPolicy {
    fun decide(modifiers: HeldModifiers, anchorIndex: Int?, clickedIndex: Int): ModifierClick {
        if (clickedIndex < 0) return ModifierClick.Open
        return when {
            modifiers.shift && anchorIndex != null ->
                ModifierClick.SelectRange(
                    fromIndex = minOf(anchorIndex, clickedIndex),
                    toIndex = maxOf(anchorIndex, clickedIndex),
                )
            modifiers.shift || modifiers.ctrl || modifiers.meta -> ModifierClick.ToggleSelection
            modifiers.alt -> ModifierClick.OpenExternal
            else -> ModifierClick.Open
        }
    }
}
