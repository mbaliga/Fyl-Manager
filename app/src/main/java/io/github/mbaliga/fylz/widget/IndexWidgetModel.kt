package io.github.mbaliga.fylz.widget

import io.github.mbaliga.fylz.index.IndexState

/** What WorkManager says about the unique index-rebuild work, reduced to what the widget needs. */
enum class RebuildWork { NONE, WAITING, RUNNING }

/** The Index widget's status word, most specific first. */
enum class IndexStatus {
    /** No folder has been added to the index. */
    NOT_SET_UP,
    PAUSED,
    REBUILDING,

    /** Enqueued, waiting for its storage-not-low constraint or a worker slot. */
    QUEUED,

    /** The last rebuild ended with an error (its message is not shown: it can name a folder). */
    FAILED,

    /** Folders are set up but no rebuild has completed yet. */
    NOT_BUILT,

    /**
     * The last rebuild completed. Not "up to date": a rebuild is a one-time WorkManager job and no
     * watcher follows the folders, so files added since are not in the index. The widget prints
     * when it was built and lets that time carry the meaning.
     */
    BUILT,
}

data class IndexWidgetModel(
    val status: IndexStatus,
    /** Indexed entries, folders included (`LocalIndexWorker` records both); the app says "items". */
    val indexedFiles: Int,
    val folderCount: Int,
    val collectionCount: Int,
    val lastCompletedAtMillis: Long?,
    /** The last rebuild stopped at a safety limit, so the count is not every file. */
    val truncated: Boolean,
) {
    /** Offer "Rebuild" only where a tap could start one: set up, not paused, not already queued. */
    val canRebuild: Boolean
        get() = status != IndexStatus.NOT_SET_UP && status != IndexStatus.PAUSED &&
            status != IndexStatus.REBUILDING && status != IndexStatus.QUEUED

    companion object {
        fun from(
            state: IndexState,
            enabledFolders: Int,
            collections: Int,
            work: RebuildWork,
        ): IndexWidgetModel {
            // The worker writes its completion a moment before WorkManager marks it finished, and
            // the widget redraws on that write, so "running" with a completion stamped after the
            // last start is a rebuild that has already ended.
            val finishedSinceStart = state.lastStartedAtMillis != null &&
                state.lastCompletedAtMillis != null &&
                state.lastCompletedAtMillis >= state.lastStartedAtMillis
            val status = when {
                enabledFolders == 0 -> IndexStatus.NOT_SET_UP
                state.paused -> IndexStatus.PAUSED
                work == RebuildWork.RUNNING && !finishedSinceStart -> IndexStatus.REBUILDING
                work == RebuildWork.WAITING -> IndexStatus.QUEUED
                state.lastError != null -> IndexStatus.FAILED
                state.lastCompletedAtMillis == null -> IndexStatus.NOT_BUILT
                else -> IndexStatus.BUILT
            }
            return IndexWidgetModel(
                status = status,
                indexedFiles = state.indexedFiles.coerceAtLeast(0),
                folderCount = enabledFolders,
                collectionCount = collections,
                lastCompletedAtMillis = state.lastCompletedAtMillis,
                truncated = state.truncated,
            )
        }
    }
}
