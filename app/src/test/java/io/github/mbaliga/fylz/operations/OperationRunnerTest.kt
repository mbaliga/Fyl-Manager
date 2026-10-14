package io.github.mbaliga.fylz.operations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.5 (A3): the whole point of running an operation on an app-scoped [CoroutineScope] rather
 * than a Compose `rememberCoroutineScope()` is that tearing down whatever is *watching* the
 * operation (a rotated-away composable) must never cancel the operation itself.
 */
class OperationRunnerTest {

    @Test
    fun `a runner job keeps running after the observing scope is cancelled`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = OperationRunner(appScope)
        val started = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()

        val deferred = runner.run(FileOperationType.COPY, "test") {
            started.complete(Unit)
            delay(300)
            completed.complete(Unit)
            "done"
        }

        // Simulates a composable's rememberCoroutineScope(): something awaits the result on a
        // scope that does NOT own the operation, then that scope is torn down mid-flight.
        val observingScope = CoroutineScope(SupervisorJob())
        observingScope.launch { deferred.await() }
        withTimeout(1000) { started.await() }
        observingScope.cancel()

        // The operation itself must still run to completion on the app scope, unaffected.
        withTimeout(1000) { completed.await() }
        assertEquals("done", deferred.await())

        appScope.cancel()
    }

    @Test
    fun `cancel stops the tracked job and clears it from operations`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = OperationRunner(appScope)
        val started = CompletableDeferred<Unit>()

        val deferred = runner.run(FileOperationType.COPY, "test") {
            started.complete(Unit)
            delay(10_000)
        }
        withTimeout(1000) { started.await() }
        val id = runner.operations.value.single().id

        runner.cancel(id)

        assertTrue(runCatching { deferred.await() }.isFailure)
        withTimeout(1000) {
            while (runner.operations.value.isNotEmpty()) delay(10)
        }
        appScope.cancel()
    }

    @Test
    fun `progress reported during the job updates the tracked operation`() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = OperationRunner(appScope)
        val readyToCheck = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()

        runner.run(FileOperationType.COPY, "Copying") { report ->
            report(OperationProgress(label = "Copying a.txt", itemIndex = 1, itemCount = 3, completedBytes = 50, totalBytes = 100))
            readyToCheck.complete(Unit)
            proceed.await()
        }
        withTimeout(1000) { readyToCheck.await() }

        val tracked = runner.operations.value.single()
        assertEquals("Copying a.txt", tracked.label)
        assertEquals(1, tracked.itemIndex)
        assertEquals(3, tracked.itemCount)
        assertEquals(50L, tracked.completedBytes)
        assertEquals(100L, tracked.totalBytes)

        proceed.complete(Unit)
        appScope.cancel()
    }
}
