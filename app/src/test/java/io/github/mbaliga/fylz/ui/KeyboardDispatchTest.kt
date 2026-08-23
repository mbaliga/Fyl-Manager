package io.github.mbaliga.fylz.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import io.github.mbaliga.fylz.workspace.KeyboardCommand
import io.github.mbaliga.fylz.workspace.KeyboardShortcutPolicy
import io.github.mbaliga.fylz.workspace.ShortcutKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hardware-key half of WP-A3: android key events translate into the gesture vocabulary
 * `KeyboardShortcutPolicy` already resolves. End-to-end pairs are asserted through the real
 * policy, so a drifted mapping table cannot silently retarget a shortcut.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardDispatchTest {

    private fun event(
        keyCode: Int,
        action: Int = AndroidKeyEvent.ACTION_DOWN,
        metaState: Int = 0,
    ) = KeyEvent(AndroidKeyEvent(0L, 0L, action, keyCode, 0, metaState))

    @Test
    fun `plain letter carries no modifiers`() {
        val gesture = event(AndroidKeyEvent.KEYCODE_A).toShortcutGesture()!!
        assertEquals(ShortcutKey.A, gesture.key)
        assertTrue(!gesture.ctrl && !gesture.shift && !gesture.alt && !gesture.meta)
    }

    @Test
    fun `key up never translates`() {
        assertNull(event(AndroidKeyEvent.KEYCODE_A, action = AndroidKeyEvent.ACTION_UP).toShortcutGesture())
    }

    @Test
    fun `unmapped keys translate to nothing`() {
        assertNull(event(AndroidKeyEvent.KEYCODE_Q).toShortcutGesture())
        assertNull(event(AndroidKeyEvent.KEYCODE_VOLUME_UP).toShortcutGesture())
    }

    @Test
    fun `ctrl a resolves to select all through the real policy`() {
        val gesture = event(
            AndroidKeyEvent.KEYCODE_A,
            metaState = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_CTRL_LEFT_ON,
        ).toShortcutGesture()!!
        assertEquals(KeyboardCommand.SELECT_ALL, KeyboardShortcutPolicy.resolve(gesture))
    }

    @Test
    fun `ctrl shift n resolves to new folder not new file`() {
        val gesture = event(
            AndroidKeyEvent.KEYCODE_N,
            metaState = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_CTRL_LEFT_ON or
                AndroidKeyEvent.META_SHIFT_ON or AndroidKeyEvent.META_SHIFT_LEFT_ON,
        ).toShortcutGesture()!!
        assertEquals(KeyboardCommand.NEW_FOLDER, KeyboardShortcutPolicy.resolve(gesture))
    }

    @Test
    fun `bare delete recycles and shift delete deletes permanently`() {
        val recycle = event(AndroidKeyEvent.KEYCODE_FORWARD_DEL).toShortcutGesture()!!
        assertEquals(KeyboardCommand.RECYCLE, KeyboardShortcutPolicy.resolve(recycle))

        val permanent = event(
            AndroidKeyEvent.KEYCODE_FORWARD_DEL,
            metaState = AndroidKeyEvent.META_SHIFT_ON or AndroidKeyEvent.META_SHIFT_LEFT_ON,
        ).toShortcutGesture()!!
        assertEquals(KeyboardCommand.PERMANENT_DELETE, KeyboardShortcutPolicy.resolve(permanent))
    }

    @Test
    fun `function keys and escape map without modifiers`() {
        assertEquals(
            KeyboardCommand.RENAME,
            KeyboardShortcutPolicy.resolve(event(AndroidKeyEvent.KEYCODE_F2).toShortcutGesture()!!),
        )
        assertEquals(
            KeyboardCommand.REFRESH,
            KeyboardShortcutPolicy.resolve(event(AndroidKeyEvent.KEYCODE_F5).toShortcutGesture()!!),
        )
        assertEquals(
            KeyboardCommand.CLEAR_SELECTION,
            KeyboardShortcutPolicy.resolve(event(AndroidKeyEvent.KEYCODE_ESCAPE).toShortcutGesture()!!),
        )
    }

    @Test
    fun `meta works where ctrl does for command shortcuts`() {
        val gesture = event(
            AndroidKeyEvent.KEYCODE_C,
            metaState = AndroidKeyEvent.META_META_ON or AndroidKeyEvent.META_META_LEFT_ON,
        ).toShortcutGesture()!!
        assertEquals(KeyboardCommand.COPY, KeyboardShortcutPolicy.resolve(gesture))
    }
}
