package io.github.mbaliga.fylz.pdf

import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P0.13: [Task.await] is the same cancellable-coroutine wrapper [SearchablePdfService] used to
 * define privately (and the now-consolidated `PdfToolService` achieved via a blocking
 * `Tasks.await` instead) before both moved onto the seam in this file -- this pins its exact
 * success/failure behavior so the move is provably "no behaviour change".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrEngineTest {

    @Test
    fun `await returns a completed Task's result`() {
        val task = Tasks.forResult("recognized text")

        val result = awaitOffMainThread(task)

        assertEquals("recognized text", result.getOrThrow())
    }

    @Test
    fun `await rethrows a failed Task's exception`() {
        val failure = IllegalStateException("recognizer unavailable")
        val task = Tasks.forException<String>(failure)

        val result = awaitOffMainThread(task)

        val thrown = assertThrows(IllegalStateException::class.java) { result.getOrThrow() }
        assertEquals("recognizer unavailable", thrown.message)
    }

    /**
     * [Task]'s single-argument listeners -- exactly what [Task.await] registers -- always run on
     * the main application thread. Robolectric's shadow main [Looper] only delivers posted work
     * when explicitly idled, and this test's own thread doubles as that main thread, so calling
     * `runBlocking { task.await() }` directly here would deadlock: nothing would ever be left free
     * to idle the looper the suspended call is itself waiting on. Runs the suspend call on a
     * second, real thread instead, and repeatedly idles the main looper from this (main) thread
     * until that thread signals it's done -- bounded, so a genuine regression fails fast rather
     * than hanging the test run the way this file's first version did.
     */
    private fun <T> awaitOffMainThread(task: Task<T>): Result<T> {
        val outcome = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        Thread {
            outcome.set(runCatching { runBlocking { task.await() } })
            done.countDown()
        }.start()
        var iterations = 0
        while (!done.await(10, TimeUnit.MILLISECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            check(++iterations < 500) { "task.await() did not complete after pumping the main looper for 5s" }
        }
        return outcome.get()
    }
}
