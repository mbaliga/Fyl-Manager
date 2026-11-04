package io.github.mbaliga.fylz.model

import android.content.Context

/** Which of Fylz's persistent stores was just written. */
enum class StoreTopic {
    OPERATION_JOURNAL,
    RECYCLE_BIN,

    /** Backup plans, runs, snapshots and media counts (not lease refreshes). */
    BACKUPS,
    LOCAL_INDEX,
}

/**
 * A one-way notice that a store was written, for surfaces outside the app's own screens (the
 * home-screen widgets) that must redraw when data changes.
 *
 * The stores only call [changed]; whoever needs to know installs [listener] once per process
 * (`FylzApplication`). So the data layer depends on nothing above it, and moving the stores into
 * the `:core:*` modules docs/ARCHITECTURE.md plans needs no change here.
 */
object StoreChanges {
    @Volatile
    var listener: ((Context, StoreTopic) -> Unit)? = null

    /** Never throws into the store write that called it. */
    fun changed(context: Context, topic: StoreTopic) {
        val current = listener ?: return
        runCatching { current(context.applicationContext ?: context, topic) }
    }
}
