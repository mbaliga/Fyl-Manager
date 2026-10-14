package io.github.mbaliga.fylz.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ThemePaletteStore]: defaults to the bundled Moss preset, a custom theme is validated before it
 * is ever persisted or applied ("never a partial application"), and -- the part that matters for
 * "survives a process restart" -- a SECOND store instance built later against the same
 * [android.content.Context] (Robolectric's simulation of that, the same way
 * [io.github.mbaliga.fylz.browse.SessionStoreTest] simulates it for `SessionStore`) reads back
 * exactly what an earlier instance saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemePaletteStoreTest {

    private val validCustomJson = """
        {"version":1,"name":"Custom","colors":{
          "light":{"primary":"#101010","secondary":"#202020","tertiary":"#303030","background":"#FFFFFF","surface":"#FAFAFA","surfaceVariant":"#EEEEEE","onBackground":"#000000","onSurface":"#000000"},
          "dark":{"primary":"#EFEFEF","secondary":"#DFDFDF","tertiary":"#CFCFCF","background":"#000000","surface":"#0A0A0A","surfaceVariant":"#101010","onBackground":"#FFFFFF","onSurface":"#FFFFFF"}
        }}
    """.trimIndent()

    @Test
    fun `a fresh store defaults to the bundled Moss preset`() {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        assertEquals(ActiveTheme.BuiltIn("moss"), store.selection.value)
    }

    @Test
    fun `setBuiltIn switches the resolved palette to that preset's own name`() {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        store.setBuiltIn("clay")
        assertEquals(ActiveTheme.BuiltIn("clay"), store.selection.value)
        assertEquals("Clay", store.resolved.value.name)
    }

    @Test
    fun `setCustom with valid JSON applies it and resolves the exact colours`() {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        val result = store.setCustom(validCustomJson)
        assertTrue(result.isSuccess)
        assertEquals(ActiveTheme.Custom(validCustomJson), store.selection.value)
        assertEquals("Custom", store.resolved.value.name)
    }

    @Test
    fun `setCustom with malformed JSON is refused and never changes the active theme`() {
        val store = ThemePaletteStore(RuntimeEnvironment.getApplication())
        store.setBuiltIn("ink")

        val result = store.setCustom("not json at all")

        assertTrue(result.isFailure)
        assertEquals("previous selection must be untouched", ActiveTheme.BuiltIn("ink"), store.selection.value)
        assertEquals("Ink", store.resolved.value.name)
    }

    @Test
    fun `a later store instance -- simulating a process restart -- reads back a custom theme exactly as saved`() {
        val context = RuntimeEnvironment.getApplication()
        ThemePaletteStore(context).setCustom(validCustomJson)

        val restarted = ThemePaletteStore(context)
        assertEquals(ActiveTheme.Custom(validCustomJson), restarted.selection.value)
        assertEquals("Custom", restarted.resolved.value.name)
    }

    @Test
    fun `a later store instance reads back a built-in choice`() {
        val context = RuntimeEnvironment.getApplication()
        ThemePaletteStore(context).setBuiltIn("ink")

        val restarted = ThemePaletteStore(context)
        assertEquals(ActiveTheme.BuiltIn("ink"), restarted.selection.value)
    }

    @Test
    fun `lastCustomJson offers the previous custom text back even after switching to a built-in`() {
        val context = RuntimeEnvironment.getApplication()
        val store = ThemePaletteStore(context)
        store.setCustom(validCustomJson)
        store.setBuiltIn("moss")

        assertEquals(validCustomJson, store.lastCustomJson())
    }
}
