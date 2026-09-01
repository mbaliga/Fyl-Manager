package io.github.mbaliga.fylz.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.github.mbaliga.fylz.workspace.ShortcutGesture
import io.github.mbaliga.fylz.workspace.ShortcutKey

/**
 * WP-A3 (docs/product/adaptive-input-plan.md): the missing half-inch between a hardware key
 * event and `workspace/KeyboardShortcutPolicy`, which had the whole command vocabulary and
 * zero listeners. This translates; the policy decides; the shell dispatches to the same
 * handlers touch uses. No command logic lives here on purpose — a mapping table is the one
 * part of keyboard support that never needs a device to be verified.
 *
 * Only [KeyEventType.KeyDown] translates. Repeats translate too (holding Delete recycles
 * once per repeat is wrong — but the dispatcher's own guards make repeats idempotent: an
 * empty selection stops the second Delete, a dialog swallows focus after the first Rename).
 */
internal fun KeyEvent.toShortcutGesture(): ShortcutGesture? {
    if (type != KeyEventType.KeyDown) return null
    val shortcutKey = when (key) {
        Key.A -> ShortcutKey.A
        Key.C -> ShortcutKey.C
        Key.F -> ShortcutKey.F
        Key.H -> ShortcutKey.H
        Key.L -> ShortcutKey.L
        Key.N -> ShortcutKey.N
        Key.O -> ShortcutKey.O
        Key.P -> ShortcutKey.P
        Key.R -> ShortcutKey.R
        Key.T -> ShortcutKey.T
        Key.V -> ShortcutKey.V
        Key.W -> ShortcutKey.W
        Key.X -> ShortcutKey.X
        Key.Z -> ShortcutKey.Z
        Key.Delete -> ShortcutKey.DELETE
        Key.Backspace -> ShortcutKey.BACKSPACE
        Key.Enter, Key.NumPadEnter -> ShortcutKey.ENTER
        Key.Escape -> ShortcutKey.ESCAPE
        Key.Tab -> ShortcutKey.TAB
        Key.DirectionLeft -> ShortcutKey.ARROW_LEFT
        Key.DirectionRight -> ShortcutKey.ARROW_RIGHT
        Key.DirectionUp -> ShortcutKey.ARROW_UP
        Key.DirectionDown -> ShortcutKey.ARROW_DOWN
        Key.Spacebar -> ShortcutKey.SPACE
        Key.F2 -> ShortcutKey.F2
        Key.F5 -> ShortcutKey.F5
        else -> return null
    }
    return ShortcutGesture(
        key = shortcutKey,
        ctrl = isCtrlPressed,
        shift = isShiftPressed,
        alt = isAltPressed,
        meta = isMetaPressed,
    )
}
