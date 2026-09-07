package io.github.mbaliga.fylz.ui.desktop

import io.github.mbaliga.fylz.desktop.DesktopItemSize
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

    @Test
    fun `every registration's height is a positive content-fit constant, not the old blanket table`() {
        // The old bug this height field replaces: every SMALL card got 152dp, every MEDIUM 216dp,
        // every LARGE 360dp, regardless of what it actually drew (Deleted files' one-row count
        // chip got the same 360dp as Storage's full legend-plus-two-CTAs). Height now genuinely
        // varies within a size class -- RECYCLE_BIN (LARGE, 144dp) and STORAGE (LARGE, 440dp), or
        // TAGS (SMALL, 112dp) and SHELF (SMALL, 216dp) -- proof no blanket switch still lurks
        // underneath.
        WidgetRegistry.entries.forEach { registration ->
            assertTrue("${registration.type} height must be positive", registration.height.value > 0f)
        }
        val largeHeights = WidgetRegistry.entries.filter { it.defaultSize == DesktopItemSize.LARGE }.map { it.height }
        assertTrue("expected LARGE widgets to differ in height, all were $largeHeights", largeHeights.toSet().size > 1)
        val smallHeights = WidgetRegistry.entries.filter { it.defaultSize == DesktopItemSize.SMALL }.map { it.height }
        assertTrue("expected SMALL widgets to differ in height, all were $smallHeights", smallHeights.toSet().size > 1)
    }

    @Test
    fun `RECYCLE_BIN's registry default size agrees with DesktopPolicy's defaultSeed`() {
        // The Build-11.5 bug this pins: the registry said MEDIUM while defaultSeed placed it
        // LARGE -- two different "defaults" for the one type. LARGE wins (see the registration's
        // own KDoc); defaultSeed's own placement is checked against this same value in
        // DesktopPolicyTest so the two can never drift apart again.
        assertEquals(DesktopItemSize.LARGE, WidgetRegistry.of(DesktopWidgetType.RECYCLE_BIN).defaultSize)
    }
}
