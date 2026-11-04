package io.github.mbaliga.fylz.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceWidgetModelTest {
    private val gib = 1024L * 1024L * 1024L

    private fun reading(id: String, primary: Boolean = false, free: Long? = 10 * gib, total: Long? = 100 * gib) =
        VolumeReading(rootId = id, primary = primary, readOnly = false, freeBytes = free, totalBytes = total)

    @Test
    fun `primary volume comes first and removable volumes are numbered only when there are several`() {
        val one = SpaceWidgetModel.from(listOf(reading("ABCD-1234"), reading("primary", primary = true)))
        assertEquals(listOf("primary", "ABCD-1234"), one.rows.map { it.rootId })
        assertEquals(listOf(VolumeKind.INTERNAL, VolumeKind.REMOVABLE), one.rows.map { it.kind })
        assertEquals(listOf(0, 0), one.rows.map { it.ordinal })

        val two = SpaceWidgetModel.from(listOf(reading("primary", primary = true), reading("SD"), reading("USB")))
        assertEquals(listOf(0, 1, 2), two.rows.map { it.ordinal })
    }

    @Test
    fun `a volume listed twice is shown once`() {
        val model = SpaceWidgetModel.from(listOf(reading("primary", primary = true), reading("SD"), reading("SD")))
        assertEquals(2, model.rows.size)
        assertEquals(listOf(0, 0), model.rows.map { it.ordinal })
    }

    @Test
    fun `used share is computed from free and total and rounded for words`() {
        val row = SpaceWidgetModel.from(listOf(reading("primary", primary = true, free = 35 * gib, total = 100 * gib))).rows.single()
        assertTrue(row.sizeKnown)
        assertEquals(650, row.usedPermille)
        assertEquals(65, row.usedPercent)

        val rounding = SpaceWidgetModel.from(listOf(reading("p", primary = true, free = 3345, total = 10_000))).rows.single()
        assertEquals(665, rounding.usedPermille)
        assertEquals(67, rounding.usedPercent)
    }

    @Test
    fun `an unreported or impossible size is unknown rather than guessed`() {
        listOf(
            reading("a", free = 0, total = 0),
            reading("b", free = null, total = 100),
            reading("c", free = 10, total = null),
            reading("d", free = 200, total = 100),
            reading("e", free = -1, total = 100),
        ).forEach { input ->
            val row = SpaceWidgetModel.from(listOf(input)).rows.single()
            assertFalse(input.rootId, row.sizeKnown)
            assertNull(input.rootId, row.usedPermille)
            assertNull(input.rootId, row.usedPercent)
        }
    }

    @Test
    fun `larger sizes show more volumes and report the rest`() {
        val model = SpaceWidgetModel.from(
            listOf(reading("primary", primary = true)) + (1..4).map { reading("vol$it") },
        )
        assertEquals(1, model.rowsFor(WidgetSize.COMPACT).size)
        assertEquals(1, model.rowsFor(WidgetSize.SMALL).size)
        assertEquals(2, model.rowsFor(WidgetSize.MEDIUM).size)
        assertEquals(2, model.rowsFor(WidgetSize.LARGE).size)
        assertEquals(3, model.hiddenAt(WidgetSize.LARGE))
        assertEquals(3, model.hiddenAt(WidgetSize.MEDIUM))
        assertEquals("primary", model.rowsFor(WidgetSize.SMALL).single().rootId)
    }

    @Test
    fun `no mounted volume is an empty model`() {
        val model = SpaceWidgetModel.from(emptyList())
        assertTrue(model.isEmpty)
        assertEquals(0, model.hiddenAt(WidgetSize.SMALL))
    }
}
