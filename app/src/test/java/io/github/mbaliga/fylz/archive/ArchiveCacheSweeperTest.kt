package io.github.mbaliga.fylz.archive

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `ArchiveCacheSweeper` (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md sections 2.3 and 2.4): the 24 h
 * `archive-work/` sweep M3.3a moved here from `ArchiveSource` (the case `ArchiveSourceTest` used to
 * hold), and the two LRU budgets with their protections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveCacheSweeperTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = 1_700_000_000_000L

    private fun sweeper(listingBudget: Long = 1_000L, entryBudget: Long = 1_000L) =
        ArchiveCacheSweeper(context, listingBudgetBytes = listingBudget, entryBudgetBytes = entryBudget, clock = { now })

    private fun file(dir: File, name: String, size: Int, ageMillis: Long): File {
        dir.mkdirs()
        return File(dir, name).apply {
            writeBytes(ByteArray(size))
            assertTrue(setLastModified(now - ageMillis))
        }
    }

    @Test
    fun `a stale workspace older than 24 hours is swept once per process, a fresh one is kept`() {
        val sweeper = sweeper()
        val stale = File(sweeper.workRoot, "stale-from-a-dead-process").apply { mkdirs(); File(this, "input").writeBytes(ByteArray(10)) }
        assertTrue(stale.setLastModified(now - TimeUnit.HOURS.toMillis(25)))
        val fresh = File(sweeper.workRoot, "fresh").apply { mkdirs(); File(this, "input").writeBytes(ByteArray(10)) }
        assertTrue(fresh.setLastModified(now - TimeUnit.HOURS.toMillis(23)))

        sweeper.sweepOnce()
        assertFalse("the stale workspace is gone", stale.exists())
        assertTrue("the fresh workspace is untouched", File(fresh, "input").exists())
        // Only the first sweep of a process runs: a workspace that ages past the cutoff later is
        // left alone until the next process.
        assertTrue(fresh.setLastModified(now - TimeUnit.HOURS.toMillis(30)))
        sweeper.sweepOnce()
        assertTrue(fresh.exists())
        sweeper.sweepStaleWorkspaces()
        assertFalse(fresh.exists())
    }

    @Test
    fun `the listing budget evicts least recently used first and protects in-flight keys and young part files`() {
        val sweeper = sweeper(listingBudget = 1_200L)
        val oldest = file(sweeper.listingsRoot, "aaa.fzl", 400, TimeUnit.HOURS.toMillis(3))
        val oldestMeta = file(sweeper.listingsRoot, "aaa.meta", 10, TimeUnit.HOURS.toMillis(3))
        val protectedOld = file(sweeper.listingsRoot, "bbb.fzl", 400, TimeUnit.HOURS.toMillis(2))
        val middle = file(sweeper.listingsRoot, "ccc.fzl", 400, TimeUnit.HOURS.toMillis(1))
        val newest = file(sweeper.listingsRoot, "ddd.fzl", 300, 0)
        val youngPart = file(sweeper.listingsRoot, "eee.1234.part", 500, TimeUnit.MINUTES.toMillis(10))
        val stalePart = file(sweeper.listingsRoot, "fff.5678.part", 500, TimeUnit.HOURS.toMillis(2))

        sweeper.enforceListingBudget(inFlightKeys = setOf("bbb"))

        assertFalse("the stale .part is always removed", stalePart.exists())
        assertTrue("a young .part is never touched", youngPart.exists())
        assertTrue("an in-flight key is never evicted", protectedOld.exists())
        assertFalse("the oldest listing goes first", oldest.exists())
        assertFalse(oldestMeta.exists())
        // After aaa: 400 (bbb) + 400 (ccc) + 300 (ddd) + 500 (part) = 1600 > 1200, so ccc goes too;
        // then 1200 <= 1200 and ddd, the most recent, stays.
        assertFalse(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `the entry budget evicts by mtime across keys, never a pinned file, and removes emptied key directories`() {
        val sweeper = sweeper(entryBudget = 1_000L)
        val key1 = File(sweeper.entriesRoot, "k1")
        val key2 = File(sweeper.entriesRoot, "k2")
        val old1 = file(key1, "0", 400, TimeUnit.HOURS.toMillis(5))
        val pinned = file(key1, "1", 400, TimeUnit.HOURS.toMillis(4))
        val old2 = file(key2, "0", 400, TimeUnit.HOURS.toMillis(3))
        val fresh2 = file(key2, "1", 300, 0)

        sweeper.enforceEntryBudget(pinned = setOf(pinned))

        assertFalse(old1.exists())
        assertTrue("pinned files are never evicted", pinned.exists())
        assertFalse(old2.exists())
        assertTrue(fresh2.exists())
        assertTrue(key1.isDirectory)
        assertTrue(key2.isDirectory)

        // Once everything under a key is gone, the key directory goes too.
        val sweeper2 = sweeper(entryBudget = 100L)
        sweeper2.enforceEntryBudget(pinned = setOf(pinned))
        assertFalse(fresh2.exists())
        assertFalse(key2.exists())
        assertTrue(pinned.exists())
    }

    @Test
    fun `under budget nothing is touched`() {
        val sweeper = sweeper(listingBudget = 10_000L, entryBudget = 10_000L)
        val listing = file(sweeper.listingsRoot, "aaa.fzl", 400, TimeUnit.DAYS.toMillis(30))
        val entry = file(File(sweeper.entriesRoot, "k"), "3", 400, TimeUnit.DAYS.toMillis(30))
        sweeper.enforceListingBudget()
        sweeper.enforceEntryBudget()
        assertTrue(listing.exists())
        assertTrue(entry.exists())
        assertEquals(64L * 1024 * 1024, ArchiveCacheSweeper.LISTING_BUDGET_BYTES)
        assertEquals(512L * 1024 * 1024, ArchiveCacheSweeper.ENTRY_BUDGET_BYTES)
    }
}
