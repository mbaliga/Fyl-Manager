package io.github.mbaliga.fylz.ui.desktop

import io.github.mbaliga.fylz.desktop.DesktopWidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM -- [WidgetRegistry] is plain data (an `Int` string-resource id, two enums), no
 * Context/Compose involved, so this needs no Robolectric, the same as [DesktopPolicyTest].
 */
class WidgetRegistryTest {

    @Test
    fun `every DesktopWidgetType has exactly one registration`() {
        val types = WidgetRegistry.entries.map { it.type }
        assertEquals(DesktopWidgetType.entries.toSet(), types.toSet())
        assertEquals(DesktopWidgetType.entries.size, types.size)
    }

    @Test
    fun `of returns a registration whose own type matches what was asked for`() {
        DesktopWidgetType.entries.forEach { type ->
            assertEquals(type, WidgetRegistry.of(type).type)
        }
    }

    @Test
    fun `every registration's default size is one of its own allowed sizes`() {
        WidgetRegistry.entries.forEach { registration ->
            assertTrue(
                "${registration.type} default ${registration.defaultSize} not in ${registration.allowedSizes}",
                registration.defaultSize in registration.allowedSizes,
            )
        }
    }

    @Test
    fun `every registration's allowedSizes is non-empty`() {
        WidgetRegistry.entries.forEach { registration ->
            assertTrue(registration.allowedSizes.isNotEmpty())
        }
    }

    @Test
    fun `every registration's displayNameRes is a distinct, real resource id`() {
        val ids = WidgetRegistry.entries.map { it.displayNameRes }
        assertTrue(ids.all { it != 0 })
        assertEquals(ids.size, ids.toSet().size)
    }
}
