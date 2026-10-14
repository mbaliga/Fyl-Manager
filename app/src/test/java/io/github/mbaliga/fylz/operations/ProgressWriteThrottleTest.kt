package io.github.mbaliga.fylz.operations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressWriteThrottleTest {

    @Test
    fun `the first update always writes`() {
        val throttle = ProgressWriteThrottle(now = { 0L })
        assertTrue(throttle.shouldWrite(completedBytes = 100, isFinal = false))
    }

    @Test
    fun `a small update within the time window does not write again`() {
        var clock = 0L
        val throttle = ProgressWriteThrottle(now = { clock })
        assertTrue(throttle.shouldWrite(completedBytes = 100, isFinal = false))

        clock = 100L // under the 250ms window
        assertFalse(throttle.shouldWrite(completedBytes = 200, isFinal = false))
    }

    @Test
    fun `an update writes once the time window elapses`() {
        var clock = 0L
        val throttle = ProgressWriteThrottle(now = { clock })
        assertTrue(throttle.shouldWrite(completedBytes = 100, isFinal = false))

        clock = 250L
        assertTrue(throttle.shouldWrite(completedBytes = 150, isFinal = false))
    }

    @Test
    fun `an update writes once 8 MiB have accumulated even within the time window`() {
        val clock = 0L
        val throttle = ProgressWriteThrottle(now = { clock })
        assertTrue(throttle.shouldWrite(completedBytes = 0, isFinal = false))

        assertFalse(throttle.shouldWrite(completedBytes = 4L * 1024 * 1024, isFinal = false))
        assertTrue(throttle.shouldWrite(completedBytes = 8L * 1024 * 1024, isFinal = false))
    }

    @Test
    fun `a final update always writes even mid-window`() {
        var clock = 0L
        val throttle = ProgressWriteThrottle(now = { clock })
        assertTrue(throttle.shouldWrite(completedBytes = 100, isFinal = false))

        clock = 10L
        assertTrue(throttle.shouldWrite(completedBytes = 137, isFinal = true))
    }

    @Test
    fun `a drop in completed bytes -- a new file starting -- resets the byte high-water mark`() {
        var clock = 0L
        val throttle = ProgressWriteThrottle(now = { clock })
        assertTrue(throttle.shouldWrite(completedBytes = 5L * 1024 * 1024, isFinal = true))

        // Still well inside the next time window: the new (much smaller) file's own progress
        // doesn't write yet either -- the reset only stops the delta being computed against the
        // *previous* file's high-water mark (which would otherwise never again cross 8 MiB).
        clock = 10L
        assertFalse(throttle.shouldWrite(completedBytes = 10, isFinal = false))

        clock = 260L
        assertTrue(throttle.shouldWrite(completedBytes = 20, isFinal = false))
    }
}
