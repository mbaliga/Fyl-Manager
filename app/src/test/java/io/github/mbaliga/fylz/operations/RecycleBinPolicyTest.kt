package io.github.mbaliga.fylz.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecycleBinPolicyTest {
    @Test
    fun `default delete refuses when recycle is unavailable`() {
        assertEquals(
            DefaultDeletePolicyDecision.REFUSE_NO_RECYCLE_ROOT,
            RecycleBinPolicy.decideDefaultDeletePolicy(
                hasRecycleRoot = false,
                canWriteRecycleRoot = false,
            ),
        )
    }

    @Test
    fun `default delete refuses when recycle root is not writable`() {
        assertEquals(
            DefaultDeletePolicyDecision.REFUSE_RECYCLE_ROOT_NOT_WRITABLE,
            RecycleBinPolicy.decideDefaultDeletePolicy(
                hasRecycleRoot = true,
                canWriteRecycleRoot = false,
            ),
        )
    }

    @Test
    fun `default delete moves to recycle when writable`() {
        assertEquals(
            DefaultDeletePolicyDecision.MOVE_TO_RECYCLE_BIN,
            RecycleBinPolicy.decideDefaultDeletePolicy(
                hasRecycleRoot = true,
                canWriteRecycleRoot = true,
            ),
        )
    }

    @Test
    fun `permanent delete requires location or advanced action and confirmation`() {
        assertFalse(RecycleBinPolicy.allowPermanentDelete(false, false, true))
        assertFalse(RecycleBinPolicy.allowPermanentDelete(true, false, false))
        assertTrue(RecycleBinPolicy.allowPermanentDelete(true, false, true))
        assertTrue(RecycleBinPolicy.allowPermanentDelete(false, true, true))
    }
}
