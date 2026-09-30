package io.github.mbaliga.fylz.archive

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the three archive caches under `cacheDir` within their bounds
 * (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` sections 2.3 and 2.4), application-scoped:
 *
 * - `archive-work/` (M3.2's staging workspaces, and `ArchiveService`'s per-operation ones): a
 *   workspace older than [STALE_WORKSPACE_MILLIS] on the first sweep of a process was left by a
 *   dead process and is deleted. M3.3a moved this sweep here from `ArchiveSource.resolve()`.
 * - `archive-listings/` (`<key>.fzl` + `<key>.meta`, [ArchiveCatalog]): capped at
 *   [listingBudgetBytes], evicting least-recently-used files by mtime; a key with a listing in
 *   flight is never evicted, and a `.part` younger than [PART_GRACE_MILLIS] is never touched.
 * - `archive-entries/<key>/<ordinal>` ([ArchiveEntryCache]): capped at [entryBudgetBytes], the
 *   same way; a pinned file (a materialised inner archive an open handle reads) is never evicted.
 *
 * Eviction never races a reader: an open descriptor stays valid after its file is unlinked, so no
 * time guard is needed beyond the `.part` grace.
 */
class ArchiveCacheSweeper(
    context: Context,
    private val listingBudgetBytes: Long = LISTING_BUDGET_BYTES,
    private val entryBudgetBytes: Long = ENTRY_BUDGET_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cacheDir: File = context.cacheDir
    private val swept = AtomicBoolean(false)

    val workRoot: File get() = File(cacheDir, ArchiveSource.WORK_DIRECTORY)
    val listingsRoot: File get() = File(cacheDir, LISTINGS_DIRECTORY)
    val entriesRoot: File get() = File(cacheDir, ENTRIES_DIRECTORY)

    /**
     * Once per process: deletes `archive-work/` workspaces older than [STALE_WORKSPACE_MILLIS] and
     * `.part` files older than [PART_GRACE_MILLIS] under the two other caches.
     */
    fun sweepOnce() {
        if (!swept.compareAndSet(false, true)) return
        sweepStaleWorkspaces()
        sweepStaleParts(listingsRoot)
        entriesRoot.listFiles().orEmpty().filter { it.isDirectory }.forEach(::sweepStaleParts)
    }

    /** For tests and for [sweepOnce]: the `archive-work/` sweep by itself. */
    fun sweepStaleWorkspaces() {
        val cutoff = clock() - STALE_WORKSPACE_MILLIS
        val stale = workRoot.listFiles().orEmpty().filter { it.lastModified() < cutoff }
        stale.forEach { workspace ->
            if (!workspace.deleteRecursively()) Log.w(TAG, "Could not sweep stale archive workspace ${workspace.name}")
        }
        if (stale.isNotEmpty()) Log.i(TAG, "Swept ${stale.size} stale archive workspace(s)")
    }

    /**
     * Brings `archive-listings/` under budget, least recently used first, skipping every file
     * whose key is in [inFlightKeys] and every `.part` younger than the grace period.
     */
    fun enforceListingBudget(inFlightKeys: Set<String> = emptySet()) {
        sweepStaleParts(listingsRoot)
        val files = listingsRoot.listFiles().orEmpty().filter { it.isFile }
        val protectedKeys = inFlightKeys
        enforceBudget(
            files = files,
            budget = listingBudgetBytes,
            protect = { file -> file.name.substringBefore('.') in protectedKeys || file.name.endsWith(PART_SUFFIX) },
        )
    }

    /** Brings `archive-entries/` under budget, least recently used first, never touching [pinned]. */
    fun enforceEntryBudget(pinned: Set<File> = emptySet()) {
        val directories = entriesRoot.listFiles().orEmpty().filter { it.isDirectory }
        directories.forEach(::sweepStaleParts)
        val files = directories.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile } }
        val pinnedPaths = pinned.mapTo(HashSet()) { it.absolutePath }
        enforceBudget(
            files = files,
            budget = entryBudgetBytes,
            protect = { file -> file.absolutePath in pinnedPaths || file.name.endsWith(PART_SUFFIX) },
        )
        // Directories emptied by eviction are removed so the cache does not accumulate keys.
        directories.forEach { dir -> if (dir.listFiles().isNullOrEmpty()) dir.delete() }
    }

    private fun enforceBudget(files: List<File>, budget: Long, protect: (File) -> Boolean) {
        var total = files.sumOf { it.length() }
        if (total <= budget) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= budget) break
            if (protect(file)) continue
            val length = file.length()
            if (file.delete()) total -= length else Log.w(TAG, "Could not evict ${file.name} from the archive cache")
        }
    }

    private fun sweepStaleParts(directory: File) {
        val cutoff = clock() - PART_GRACE_MILLIS
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) && it.lastModified() < cutoff }
            .forEach { part -> if (!part.delete()) Log.w(TAG, "Could not remove stale ${part.name}") }
    }

    companion object {
        private const val TAG = "ArchiveCacheSweeper"

        const val LISTINGS_DIRECTORY = "archive-listings"
        const val ENTRIES_DIRECTORY = "archive-entries"

        /** The suffix of a file still being written (`<name>.<nonce>.part`). */
        const val PART_SUFFIX = ".part"

        /** The on-disk listing cache's size cap (section 2.3). */
        const val LISTING_BUDGET_BYTES: Long = 64L * 1024L * 1024L

        /** The materialised-entry cache's size cap (section 2.4, `ENTRY_CACHE_BUDGET`). */
        const val ENTRY_BUDGET_BYTES: Long = 512L * 1024L * 1024L

        /** A workspace this old on the first sweep of a process was left by a dead process. */
        val STALE_WORKSPACE_MILLIS: Long = TimeUnit.HOURS.toMillis(24)

        /** A `.part` younger than this may still be receiving bytes. */
        val PART_GRACE_MILLIS: Long = TimeUnit.HOURS.toMillis(1)
    }
}
