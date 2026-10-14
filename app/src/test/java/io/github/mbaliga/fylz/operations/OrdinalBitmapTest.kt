package io.github.mbaliga.fylz.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ordinal bitmap that crosses Binder and lives in `extract_plans.ordinals` (M3.4). Plain JVM. */
class OrdinalBitmapTest {

    @Test
    fun `set, contains, cardinality, last and ordinals agree`() {
        val bitmap = OrdinalBitmap.of(3, 1, 7, 8, 9)
        assertTrue(1 in bitmap)
        assertTrue(9 in bitmap)
        assertFalse(2 in bitmap)
        assertFalse(-1 in bitmap)
        assertEquals(5, bitmap.cardinality)
        assertEquals(9, bitmap.last)
        assertEquals(listOf(1, 3, 7, 8, 9), bitmap.ordinals().toList())
        assertFalse(bitmap.isEmpty)
        assertEquals(-1, OrdinalBitmap().last)
        assertTrue(OrdinalBitmap().isEmpty)
        assertThrows(IllegalArgumentException::class.java) { OrdinalBitmap().set(-1) }
    }

    @Test
    fun `ranges are the inclusive runs, ascending and non-overlapping`() {
        assertEquals(listOf(1..1, 3..3, 7..9), OrdinalBitmap.of(3, 1, 7, 8, 9).ranges())
        assertEquals(listOf(0..4), OrdinalBitmap.of(0, 1, 2, 3, 4).ranges())
        assertEquals(emptyList<IntRange>(), OrdinalBitmap().ranges())
        assertEquals(listOf(200_000..200_000), OrdinalBitmap.of(200_000).ranges())
    }

    @Test
    fun `the byte array round trips through Binder's shape, bit 8i plus j of byte i being ordinal 8i plus j`() {
        val bitmap = OrdinalBitmap.of(0, 9, 17, 4095)
        val bytes = bitmap.toByteArray()
        assertEquals(512, bytes.size)
        assertEquals(1, bytes[0].toInt() and 0xFF)
        assertEquals(2, bytes[1].toInt() and 0xFF)
        assertEquals(2, bytes[2].toInt() and 0xFF)
        assertEquals(0x80, bytes[511].toInt() and 0xFF)
        assertEquals(bitmap, OrdinalBitmap.fromByteArray(bytes))
        assertEquals(bitmap.hashCode(), OrdinalBitmap.fromByteArray(bytes).hashCode())
        assertEquals(OrdinalBitmap(), OrdinalBitmap.fromByteArray(ByteArray(0)))
        // 200,000 ordinals is about 25 KiB.
        val all = OrdinalBitmap().apply { repeat(200_000) { set(it) } }
        assertEquals(25_000, all.toByteArray().size)
    }

    @Test
    fun `after clears everything below the resume point and everything in minus, without touching the original`() {
        val bitmap = OrdinalBitmap.of(1, 2, 5, 8, 9, 12)
        val resumed = bitmap.after(5, minus = OrdinalBitmap.of(8, 12))
        assertEquals(listOf(5, 9), resumed.ordinals().toList())
        assertEquals(listOf(1, 2, 5, 8, 9, 12), bitmap.ordinals().toList())
        assertEquals(listOf(1, 2, 5, 8, 9, 12), bitmap.after(0).ordinals().toList())
        assertTrue(bitmap.after(13).isEmpty)
        val copy = bitmap.copy()
        copy.clear(1)
        assertTrue(1 in bitmap)
        assertFalse(1 in copy)
        assertEquals("OrdinalBitmap(1..2, 5, 8..9, 12)", bitmap.toString())
    }
}
