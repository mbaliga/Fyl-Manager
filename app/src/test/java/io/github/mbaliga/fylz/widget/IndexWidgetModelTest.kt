package io.github.mbaliga.fylz.widget

import io.github.mbaliga.fylz.index.IndexState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexWidgetModelTest {

    private fun status(state: IndexState, folders: Int = 2, work: RebuildWork = RebuildWork.NONE) =
        IndexWidgetModel.from(state, folders, collections = 1, work = work).status

    @Test
    fun `status words follow the most specific condition`() {
        val built = IndexState(lastStartedAtMillis = 100, lastCompletedAtMillis = 200, indexedFiles = 12_408)
        assertEquals(IndexStatus.NOT_SET_UP, status(built, folders = 0))
        assertEquals(IndexStatus.PAUSED, status(built.copy(paused = true), work = RebuildWork.RUNNING))
        assertEquals(IndexStatus.QUEUED, status(built, work = RebuildWork.WAITING))
        assertEquals(IndexStatus.FAILED, status(built.copy(lastError = "Index root Private is unavailable.")))
        assertEquals(IndexStatus.NOT_BUILT, status(IndexState()))
        assertEquals(IndexStatus.BUILT, status(built))
    }

    @Test
    fun `running work counts as rebuilding only until the worker records its completion`() {
        val started = IndexState(lastStartedAtMillis = 300, lastCompletedAtMillis = 200)
        assertEquals(IndexStatus.REBUILDING, status(started, work = RebuildWork.RUNNING))

        val finished = IndexState(lastStartedAtMillis = 300, lastCompletedAtMillis = 310)
        assertEquals(IndexStatus.BUILT, status(finished, work = RebuildWork.RUNNING))
    }

    @Test
    fun `rebuild is offered only where a tap could start one`() {
        val built = IndexState(lastStartedAtMillis = 1, lastCompletedAtMillis = 2)
        assertTrue(IndexWidgetModel.from(built, 1, 0, RebuildWork.NONE).canRebuild)
        assertTrue(IndexWidgetModel.from(built.copy(lastError = "x"), 1, 0, RebuildWork.NONE).canRebuild)
        assertFalse(IndexWidgetModel.from(built, 0, 0, RebuildWork.NONE).canRebuild)
        assertFalse(IndexWidgetModel.from(built.copy(paused = true), 1, 0, RebuildWork.NONE).canRebuild)
        assertFalse(IndexWidgetModel.from(IndexState(lastStartedAtMillis = 5), 1, 0, RebuildWork.RUNNING).canRebuild)
        assertFalse(IndexWidgetModel.from(built, 1, 0, RebuildWork.WAITING).canRebuild)
    }

    @Test
    fun `the error text never reaches the model, only the status`() {
        val model = IndexWidgetModel.from(IndexState(lastError = "Index root Medical scans is unavailable."), 1, 0, RebuildWork.NONE)
        assertEquals(IndexStatus.FAILED, model.status)
        assertFalse(model.toString().contains("Medical"))
    }

    @Test
    fun `counts carry through and never go negative`() {
        val model = IndexWidgetModel.from(IndexState(indexedFiles = -3, truncated = true), 3, 4, RebuildWork.NONE)
        assertEquals(0, model.indexedFiles)
        assertEquals(3, model.folderCount)
        assertEquals(4, model.collectionCount)
        assertTrue(model.truncated)
    }
}
