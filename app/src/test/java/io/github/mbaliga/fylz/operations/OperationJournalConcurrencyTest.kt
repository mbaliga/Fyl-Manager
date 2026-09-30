package io.github.mbaliga.fylz.operations

import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** P1.1: concurrent writers -- e.g. more than one service constructing its own [OperationJournal]
 * against the same underlying SQLite file -- must not corrupt or drop records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OperationJournalConcurrencyTest {

    @Test
    fun `concurrent puts from multiple threads all persist without corruption`() {
        val journal = OperationJournal(RuntimeEnvironment.getApplication())
        val threadCount = 12
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val failures = CopyOnWriteArrayList<Throwable>()

        val threads = (0 until threadCount).map { index ->
            Thread {
                ready.countDown()
                go.await()
                try {
                    journal.put(
                        FileOperation(
                            id = "operation-$index",
                            type = FileOperationType.COPY,
                            items = listOf(
                                OperationItem(
                                    id = "item-$index",
                                    source = Uri.parse("content://test/source-$index"),
                                    displayName = "file-$index.txt",
                                ),
                            ),
                        ),
                    )
                } catch (t: Throwable) {
                    failures += t
                } finally {
                    done.countDown()
                }
            }
        }
        threads.forEach(Thread::start)
        ready.await()
        go.countDown()
        done.await()
        threads.forEach(Thread::join)

        assertTrue("concurrent put() threw: $failures", failures.isEmpty())
        val ids = journal.list().map(FileOperation::id).toSet()
        assertEquals(threadCount, ids.size)
        repeat(threadCount) { index -> assertTrue(ids.contains("operation-$index")) }
    }
}
