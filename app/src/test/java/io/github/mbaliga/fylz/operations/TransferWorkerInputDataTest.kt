package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1.5: [TransferWorker.inputData]'s [nameOverridesFrom] round trip -- `androidx.work.Data` has
 * no map type, so a Preflight sheet's per-item auto-rename choices travel as two parallel
 * `Uri`/`String` arrays; this proves they come back out paired correctly, not just that each
 * array on its own looks right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferWorkerInputDataTest {

    private fun uri(id: Int) = Uri.parse("content://transfer-worker-test/$id")

    @Test
    fun `nameOverrides round trip through inputData paired correctly`() {
        val overrides = mapOf(uri(1) to "renamed one.txt", uri(2) to "renamed two.txt")

        val data = TransferWorker.inputData(FileOperationType.COPY, listOf(uri(1), uri(2)), uri(99), ConflictPolicy.KEEP_BOTH, overrides)

        assertEquals(overrides, nameOverridesFrom(data))
    }

    @Test
    fun `no nameOverrides round trips to an empty map, not null or a crash`() {
        val data = TransferWorker.inputData(FileOperationType.MOVE, listOf(uri(1)), uri(99), ConflictPolicy.KEEP_BOTH)

        assertTrue(nameOverridesFrom(data).isEmpty())
    }

    @Test
    fun `a single override round trips correctly, not just a multi-entry map`() {
        val overrides = mapOf(uri(7) to "only one.txt")

        val data = TransferWorker.inputData(FileOperationType.COPY, listOf(uri(7)), uri(99), ConflictPolicy.KEEP_BOTH, overrides)

        assertEquals(overrides, nameOverridesFrom(data))
    }
}
