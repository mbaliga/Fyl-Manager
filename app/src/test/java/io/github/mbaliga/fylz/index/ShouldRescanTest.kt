package io.github.mbaliga.fylz.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1.12: [shouldRescan]'s pure decision table -- see its own KDoc for what each case means. */
class ShouldRescanTest {

    @Test
    fun `no MediaStore signal (removable or third-party scope) always rescans`() {
        assertTrue(shouldRescan(previousGeneration = null, currentGeneration = null))
        assertTrue(shouldRescan(previousGeneration = 7L, currentGeneration = null))
    }

    @Test
    fun `a first-ever scan rescans even when MediaStore already has a generation`() {
        assertTrue(shouldRescan(previousGeneration = null, currentGeneration = 3L))
    }

    @Test
    fun `an unchanged generation skips the rescan`() {
        assertFalse(shouldRescan(previousGeneration = 5L, currentGeneration = 5L))
    }

    @Test
    fun `a changed generation rescans`() {
        assertTrue(shouldRescan(previousGeneration = 5L, currentGeneration = 6L))
    }
}
