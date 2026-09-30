package io.github.mbaliga.fylz.ui.actions

import io.github.mbaliga.fylz.ui.theme.ActiveTheme
import io.github.mbaliga.fylz.ui.theme.ThemePaletteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ThemeFlow]: the mandatory preview step shows exactly what was pasted, unmodified, and nothing
 * is applied to [ThemePaletteStore] until Apply -- Cancel or Back at any point leaves the active
 * theme untouched, and a malformed Apply is refused with an inline error, never a crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeFlowTest {

    private val validJson = """
        {"version":1,"name":"Custom","colors":{
          "light":{"primary":"#101010","secondary":"#202020","tertiary":"#303030","background":"#FFFFFF","surface":"#FAFAFA","surfaceVariant":"#EEEEEE","onBackground":"#000000","onSurface":"#000000"},
          "dark":{"primary":"#EFEFEF","secondary":"#DFDFDF","tertiary":"#CFCFCF","background":"#000000","surface":"#0A0A0A","surfaceVariant":"#101010","onBackground":"#FFFFFF","onSurface":"#FFFFFF"}
        }}
    """.trimIndent()

    private fun newFlow(): ThemeFlow {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        return ThemeFlow(store) { }
    }

    @Test
    fun `choosing a built-in applies it immediately and closes the picker`() {
        val flow = newFlow()
        flow.openPicker()
        flow.chooseBuiltIn("clay")
        assertFalse(flow.pickerOpen)
    }

    @Test
    fun `the preview step shows exactly the pasted text, not a reformatted copy`() {
        val flow = newFlow()
        val pasted = " {\n  \"version\": 1\n}\n  "
        flow.beginCustom()
        flow.onCustomTextChange(pasted)
        flow.requestPreview()
        assertEquals(pasted, flow.previewText)
    }

    @Test
    fun `nothing is applied until Apply -- cancelling the preview leaves no trace`() {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        val flow = ThemeFlow(store) { }
        val before = store.selection.value

        flow.openPicker()
        flow.beginCustom()
        flow.onCustomTextChange(validJson)
        flow.requestPreview()
        flow.dismiss()

        assertEquals(before, store.selection.value)
    }

    @Test
    fun `Back returns to editing with the text intact and nothing applied`() {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        val flow = ThemeFlow(store) { }
        val before = store.selection.value

        flow.beginCustom()
        flow.onCustomTextChange(validJson)
        flow.requestPreview()
        flow.backToEditing()

        assertNull(flow.previewText)
        assertEquals(validJson, flow.customText)
        assertEquals(before, store.selection.value)
    }

    @Test
    fun `Apply with valid JSON persists it and closes every step`() {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        val flow = ThemeFlow(store) { }

        flow.beginCustom()
        flow.onCustomTextChange(validJson)
        flow.requestPreview()
        flow.applyCustom()

        assertEquals(ActiveTheme.Custom(validJson), store.selection.value)
        assertFalse(flow.pickerOpen)
        assertNull(flow.previewText)
    }

    @Test
    fun `Apply with malformed JSON shows a specific inline error and applies nothing`() {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        store.setBuiltIn("ink")
        val flow = ThemeFlow(store) { }

        flow.beginCustom()
        flow.onCustomTextChange("not json at all")
        flow.requestPreview()
        flow.applyCustom()

        assertTrue(flow.previewError!!.isNotBlank())
        // The dialog stays open on the preview step, showing the exact text, for the user to fix.
        assertEquals("not json at all", flow.previewText)
        assertEquals(ActiveTheme.BuiltIn("ink"), store.selection.value)
    }
}
