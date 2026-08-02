package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecycleBinPolicyTest {
    @Test
    fun `default delete refuses when recycle is unavailable`() {
        assertTrue(RecycleBinPolicy.decideDefaultDelete(null, false) is DeleteDecision.Refuse)
    }

    @Test
    fun `default delete moves to recycle when writable`() {
        val decision = RecycleBinPolicy.decideDefaultDelete(Uri.parse("content://provider/root/.fylz-trash"), true)
        assertTrue(decision is DeleteDecision.MoveToRecycleBin)
    }

    @Test
    fun `permanent delete requires location or advanced action and confirmation`() {
        assertFalse(RecycleBinPolicy.allowPermanentDelete(false, false, true))
        assertFalse(RecycleBinPolicy.allowPermanentDelete(true, false, false))
        assertTrue(RecycleBinPolicy.allowPermanentDelete(true, false, true))
        assertTrue(RecycleBinPolicy.allowPermanentDelete(false, true, true))
    }
}
