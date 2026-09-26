package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Most cases here test the pure [OperationRetryPolicy.canRetry]/[OperationRetryPolicy.isMoveCleanupRetry]
 *  functions directly and need no Android classes at all; the [OperationRetryPolicy.plan] cases
 *  build a real [FileOperation] with [Uri.parse]-backed URIs, which needs Robolectric. */
@RunWith(RobolectricTestRunner::class)
class OperationRetryPolicyTest {
    @Test
    fun `failed copy with one shared destination can retry`() {
        assertTrue(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.FAILED,
                incompleteItemCount = 2,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `destructive operation cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.PERMANENT_DELETE,
                state = OperationState.FAILED,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `copy without destination cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.NEEDS_ATTENTION,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = false,
                incompleteItemsShareDestination = false,
            ),
        )
    }

    @Test
    fun `items with different destinations cannot retry together`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteItemCount = 2,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = false,
            ),
        )
    }

    @Test
    fun `a PARTIAL operation can retry, replaying only its failed items`() {
        assertTrue(
            "P1.7: PARTIAL means some items succeeded and some failed; retrying replays the failed ones",
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.PARTIAL,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `plan for a PARTIAL operation replays exactly the non-succeeded items`() {
        val operation = FileOperation(
            id = "op-partial",
            type = FileOperationType.COPY,
            items = listOf(
                OperationItem(
                    source = Uri.parse("content://fylz/a"),
                    destination = Uri.parse("content://fylz/tree/dest"),
                    displayName = "a.txt",
                    state = OperationState.SUCCEEDED,
                ),
                OperationItem(
                    source = Uri.parse("content://fylz/b"),
                    destination = Uri.parse("content://fylz/tree/dest"),
                    displayName = "b.txt",
                    state = OperationState.FAILED,
                    errorCode = "IOException",
                ),
            ),
            state = OperationState.PARTIAL,
        )

        val plan = OperationRetryPolicy.plan(operation)

        assertTrue(plan is OperationRetryPlan.Transfer)
        assertEquals(listOf(Uri.parse("content://fylz/b")), (plan as OperationRetryPlan.Transfer).sourceUris)
    }

    @Test
    fun `successful operation cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.MOVE,
                state = OperationState.SUCCEEDED,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `verified move copy uses cleanup retry instead of transfer replay`() {
        assertTrue(
            OperationRetryPolicy.isMoveCleanupRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteErrorCodes = listOf(OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING),
                allIncompleteItemsHaveDestination = true,
            ),
        )
    }

    @Test
    fun `mixed move errors cannot be treated as cleanup only`() {
        assertFalse(
            OperationRetryPolicy.isMoveCleanupRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteErrorCodes = listOf(
                    OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING,
                    "COPY_FAILED",
                ),
                allIncompleteItemsHaveDestination = true,
            ),
        )
    }

    @Test
    fun `cleanup retry requires a verified destination URI`() {
        assertFalse(
            OperationRetryPolicy.isMoveCleanupRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteErrorCodes = listOf(OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING),
                allIncompleteItemsHaveDestination = false,
            ),
        )
    }

    // ------------------------------------------------------------------ M3.4: planned extractions

    private val archiveEntry = Uri.parse("content://io.github.mbaliga.fylz.archives/document/ZW50cnk")
    private val destinationFolder = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary%3A/document/primary%3Adest")

    private fun plannedExtract(state: OperationState, itemState: OperationState = OperationState.FAILED) = FileOperation(
        id = "extract-1",
        type = FileOperationType.EXTRACT,
        items = listOf(
            OperationItem(source = archiveEntry, destination = destinationFolder, displayName = "docs", state = OperationState.SUCCEEDED),
            OperationItem(source = archiveEntry, destination = destinationFolder, displayName = "hello.txt", state = itemState, errorCode = "ARCHIVE_CRC_MISMATCH"),
        ),
        conflictPolicy = ConflictPolicy.SKIP,
        state = state,
        destination = destinationFolder,
    )

    @Test
    fun `a planned extraction in a retryable state is re-claimed as the same operation`() {
        listOf(OperationState.FAILED, OperationState.PARTIAL, OperationState.CANCELLED, OperationState.NEEDS_ATTENTION, OperationState.INTERRUPTED).forEach { state ->
            val operation = plannedExtract(state)
            assertTrue(OperationRetryPolicy.isPlannedExtract(operation))
            assertEquals("$state", OperationRetryPlan.ReclaimExtract("extract-1"), OperationRetryPolicy.plan(operation))
            assertTrue(OperationRetryPolicy.canRetry(operation))
            assertEquals("Retry extraction", OperationRetryPolicy.actionLabel(operation))
        }
    }

    @Test
    fun `a planned extraction that succeeded, is queued, running or has nothing unfinished is not retryable`() {
        assertEquals(null, OperationRetryPolicy.plan(plannedExtract(OperationState.SUCCEEDED, itemState = OperationState.SUCCEEDED)))
        assertEquals(null, OperationRetryPolicy.plan(plannedExtract(OperationState.QUEUED)))
        assertEquals(null, OperationRetryPolicy.plan(plannedExtract(OperationState.RUNNING)))
        assertEquals(null, OperationRetryPolicy.plan(plannedExtract(OperationState.PAUSED_BY_SYSTEM)))
        assertEquals("every item succeeded", null, OperationRetryPolicy.plan(plannedExtract(OperationState.FAILED, itemState = OperationState.SUCCEEDED)))
    }

    @Test
    fun `a legacy extraction row -- a plain file source and no destination -- is never retryable`() {
        val legacy = FileOperation(
            id = "legacy-extract",
            type = FileOperationType.EXTRACT,
            items = listOf(OperationItem(source = Uri.parse("content://io.github.mbaliga.fylz.files/document/primary%3Aa.zip"), destination = destinationFolder, displayName = "a.zip", state = OperationState.FAILED)),
            state = OperationState.FAILED,
        )
        assertFalse(OperationRetryPolicy.isPlannedExtract(legacy))
        assertEquals(null, OperationRetryPolicy.plan(legacy))
        assertEquals("Retry unavailable", OperationRetryPolicy.actionLabel(legacy))
        val mixedSources = plannedExtract(OperationState.FAILED).let { it.copy(items = it.items + OperationItem(source = Uri.parse("content://other/x"), destination = destinationFolder, displayName = "x", state = OperationState.FAILED)) }
        assertFalse(OperationRetryPolicy.isPlannedExtract(mixedSources))
        assertEquals("the copy rules do not apply either: an archive source is not a file to copy", null, OperationRetryPolicy.plan(mixedSources.copy(type = FileOperationType.EXTRACT)))
    }

    // ------------------------------------------------------------------ M3.5: planned creates

    private val sourceFile = Uri.parse("content://io.github.mbaliga.fylz.files/document/primary%3Asrc.txt")

    private fun plannedCreate(state: OperationState, itemState: OperationState = OperationState.FAILED) = FileOperation(
        id = "create-1",
        type = FileOperationType.ARCHIVE,
        items = listOf(OperationItem(source = sourceFile, destination = destinationFolder, displayName = "src.txt", state = itemState, errorCode = "ARCHIVE_WRITE_FAILED")),
        conflictPolicy = ConflictPolicy.SKIP,
        state = state,
        destination = destinationFolder,
    )

    @Test
    fun `a planned create in a retryable state is re-claimed as the same operation`() {
        listOf(OperationState.FAILED, OperationState.PARTIAL, OperationState.CANCELLED, OperationState.NEEDS_ATTENTION, OperationState.INTERRUPTED).forEach { state ->
            val operation = plannedCreate(state)
            assertTrue(OperationRetryPolicy.isPlannedCreate(operation))
            assertEquals("$state", OperationRetryPlan.ReclaimCreate("create-1"), OperationRetryPolicy.plan(operation))
            assertTrue(OperationRetryPolicy.canRetry(operation))
            assertEquals("Retry compression", OperationRetryPolicy.actionLabel(operation))
        }
    }

    @Test
    fun `a planned create that succeeded, is queued, running or has nothing unfinished is not retryable`() {
        assertEquals(null, OperationRetryPolicy.plan(plannedCreate(OperationState.SUCCEEDED, itemState = OperationState.SUCCEEDED)))
        assertEquals(null, OperationRetryPolicy.plan(plannedCreate(OperationState.QUEUED)))
        assertEquals(null, OperationRetryPolicy.plan(plannedCreate(OperationState.RUNNING)))
        assertEquals(null, OperationRetryPolicy.plan(plannedCreate(OperationState.PAUSED_BY_SYSTEM)))
        assertEquals("every item succeeded", null, OperationRetryPolicy.plan(plannedCreate(OperationState.FAILED, itemState = OperationState.SUCCEEDED)))
    }

    @Test
    fun `a legacy zip4j archive row -- no top-level destination -- is never retryable`() {
        val legacy = FileOperation(
            id = "legacy-create",
            type = FileOperationType.ARCHIVE,
            items = listOf(OperationItem(source = sourceFile, destination = destinationFolder, displayName = "src.txt", state = OperationState.FAILED)),
            state = OperationState.FAILED,
            // No top-level destination: exactly what ArchiveService.createZip's own FileOperation
            // never sets, unlike CompressPlanner's (isPlannedCreate's own distinguishing signal).
        )
        assertFalse(OperationRetryPolicy.isPlannedCreate(legacy))
        assertEquals(null, OperationRetryPolicy.plan(legacy))
        assertEquals("Retry unavailable", OperationRetryPolicy.actionLabel(legacy))
    }
}
