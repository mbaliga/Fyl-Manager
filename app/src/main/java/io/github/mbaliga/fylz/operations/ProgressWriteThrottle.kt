package io.github.mbaliga.fylz.operations

/**
 * P1.2: decides whether an in-flight byte-progress update is worth persisting to the journal.
 * Before this, [FileOperationService]'s copy loop called `journal.put(current)` on every single
 * buffer read -- the brief's own words, "the code rewrites the whole journal... on every 8 KiB".
 * This writes no more often than every [minIntervalMillis] or every [minDeltaBytes], whichever
 * comes first, and always lets a final update (the item finishing, [isFinal]) through immediately
 * so completion is never lost behind a throttle window.
 *
 * [completedBytes] is per file, not per operation: a folder copy's recursion restarts it at zero
 * for every child. A drop in [completedBytes] since the last write is read as "a new file just
 * started" and resets the byte high-water mark, so the next file's own progress is judged against
 * its own bytes -- not computed as a (nonsensical, always-negative, never-due) delta against the
 * previous file's.
 */
internal class ProgressWriteThrottle(
    private val minIntervalMillis: Long = 250L,
    private val minDeltaBytes: Long = 8L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastWrittenAtMillis: Long? = null
    private var lastWrittenBytes: Long = 0L

    fun shouldWrite(completedBytes: Long, isFinal: Boolean): Boolean {
        if (completedBytes < lastWrittenBytes) lastWrittenBytes = 0L

        if (isFinal) {
            lastWrittenAtMillis = now()
            lastWrittenBytes = completedBytes
            return true
        }

        val nowMillis = now()
        val elapsedDue = lastWrittenAtMillis?.let { nowMillis - it >= minIntervalMillis } ?: true
        val bytesDue = completedBytes - lastWrittenBytes >= minDeltaBytes
        val due = elapsedDue || bytesDue
        if (due) {
            lastWrittenAtMillis = nowMillis
            lastWrittenBytes = completedBytes
        }
        return due
    }
}
