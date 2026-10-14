package io.github.mbaliga.fylz.decoder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How one decoder call ended, for callers that must tell a slow archive from a dead process
 * (docs/agent/DESIGN-M32-SEEKABLE-PFD.md section 2.5): a compressed tarball's header pass
 * decompresses the whole stream, so a multi-GB `.tar.xz` *will* hit the structure budget, and
 * the UI must say "took too long to read" rather than "could not be read safely".
 */
sealed class DecoderCall<out T> {
    /** The service answered. For `inspectArchive` the answer may still be an engine refusal --
     * that is carried in the value's own `outcome`, not here. */
    data class Ok<T>(val value: T) : DecoderCall<T>()

    /** The deadline passed; the call was abandoned and the connection dropped. */
    data object TimedOut : DecoderCall<Nothing>()

    /** The process died or the bind failed; the connection was dropped and the next call rebinds. */
    data object Failed : DecoderCall<Nothing>()
}

/**
 * The client half of the [DecoderService] boundary (docs/agent/MASTER_PLAN.md section 4.4):
 * binds lazily, enforces a per-call budget, and treats a timeout or a crash the same way --
 * drop the connection and rebind fresh on the next call, never propagate either as a thrown
 * exception. `ping`/`sniff` return a failure value (`false`/`null`) instead, matching "a crash
 * marks that file unsafe to preview; it never crashes the app"; [inspectArchive] and
 * [callStreaming] return a [DecoderCall] so their callers can distinguish the two.
 *
 * **What the timeout really does.** Each Binder transaction runs in a job owned by the client's
 * own [transactions] scope, deliberately *not* a child of the caller's coroutine, and the budget
 * wraps only the wait for that job. So when the deadline passes the caller gets its failure
 * value at once and the client unbinds: the transaction is abandoned on the calling side, not
 * completed, and losing the last binding is what lets the platform reap the isolated
 * `:decoders` process. Nothing here can interrupt the Binder thread over there -- it keeps
 * running the native call until the process is reaped -- and the abandoned job stays parked
 * on the transaction until it returns, normally with a [android.os.DeadObjectException] once
 * the process is gone, which the job swallows with a single warning log.
 *
 * **Streaming calls** ([callStreaming], M3.3, `docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md`
 * section 2.2) carry a full listing or an entry's bytes out of `:decoders` through a pipe the
 * client creates: `block` gets the write end, the caller's `drain` reads the read end until EOF,
 * and the budget is **inactivity** rather than a flat deadline -- the call is abandoned only when
 * neither the sink bytes nor the archive descriptor's shared file offset (polled with
 * `lseek(SEEK_CUR)` on this process's own copy; the offset is shared with the Binder dup) has
 * moved for `inactivityMillis`. A multi-GB header pass, a solid-7z lead-in and a skip over a large
 * body all move the offset; a hung process moves nothing.
 *
 * **Concurrency** (M3.3 makes the client concurrent for the first time): transactions and drains
 * run on a dedicated fixed pool of [STREAM_THREADS] threads (not `Dispatchers.IO`, whose 64 threads
 * previews and transfers can fill while waiting on fills that themselves need threads); each
 * binding carries a **generation**, and every `dropConnection` names the generation it means, so
 * a stale victim (an old binding's callback, an idle timer armed for an earlier binding) never
 * unbinds a newer connection; an **in-flight counter** arms the idle timer only at zero and every
 * call start cancels it; a transaction that fails because *another* call's timeout dropped the
 * shared connection retries once on a fresh binding. After [idleUnbindMillis] with nothing in
 * flight the client unbinds (section 2.9), so `:decoders` costs nothing between browsing sessions
 * and comes back on the next call.
 *
 * **The extraction instance** ([extraction], M3.4, `docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md`
 * section 2.3): bulk extraction runs on a second isolated process, `:decoders:extract`, bound with
 * `bindIsolatedService(intent, BIND_AUTO_CREATE, "extract", executor, connection)`, so a browse
 * call's timeout never reaps a running extraction and an extraction's abandonment never reaps the
 * browsing process. [extraction] hands out a **separate** `DecoderClient` for it -- the same
 * generation/liveness machinery over a different binding -- with no idle timer (the extractor
 * calls [unbind] when the operation ends), no transparent retry (a transport loss is the
 * extractor's to handle) and its transactions on `Dispatchers.IO` rather than the fixed pool (an
 * abandoned extraction transaction would otherwise hold one of four threads while the next queued
 * extraction waited for it). The real factory needs a `Context`; tests inject their own through
 * `extractionFactory`.
 *
 * [bind]/[unbind] are the only seam onto real Android IPC, so tests can drive the exact
 * [ServiceConnection] callbacks (a hang, a disconnect) without a real isolated process --
 * genuine cross-process kill-on-timeout and crash-recovery behaviour is a device-only concern,
 * covered in docs/agent/DEVICE_CHECKS.md rather than here. The client never closes a caller's
 * descriptor.
 */
class DecoderClient(
    private val bind: (ServiceConnection) -> Boolean,
    private val unbind: (ServiceConnection) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** [NO_IDLE_UNBIND] keeps the binding until [unbind] is called (the extraction instance). */
    private val idleUnbindMillis: Long = IDLE_UNBIND_MILLIS,
    /** How often a streaming call checks for progress; the inactivity budget is counted in these steps. */
    private val livenessPollMillis: Long = LIVENESS_POLL_MILLIS,
    /** The archive descriptor's shared file offset, or `null` when it cannot be read (then only sink bytes count). */
    private val offsetProbe: (ParcelFileDescriptor) -> Long? = ::sharedOffset,
    /** Where transactions and drains run; `null` is the dedicated fixed pool of [STREAM_THREADS]. */
    transactionDispatcher: CoroutineDispatcher? = null,
    /** Whether a transaction dropped by *another* call's timeout is retried once on a fresh binding. */
    private val retryOnDrop: Boolean = true,
    /** Makes the client for the isolated extraction instance; `null` when this client cannot provide one. */
    private val extractionFactory: (() -> DecoderClient)? = null,
    /** Makes the client for the isolated write instance (M3.5); `null` when this client cannot provide one. */
    private val writerFactory: (() -> DecoderClient)? = null,
) {
    constructor(context: Context, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) : this(
        bind = { connection ->
            context.bindService(Intent(context, DecoderService::class.java), connection, Context.BIND_AUTO_CREATE)
        },
        unbind = { connection -> context.unbindService(connection) },
        timeoutMillis = timeoutMillis,
        extractionFactory = { forExtraction(context) },
        writerFactory = { forWriter(context) },
    )

    /**
     * A fresh client bound to the isolated extraction instance (`:decoders:extract`), for one
     * extraction operation: the caller runs its `callStreaming` calls on it and then [unbind]s it,
     * which also reaps the process when the extraction was abandoned. Throws when this client was
     * built without a factory (a test client that never asked for one).
     */
    fun extraction(): DecoderClient = checkNotNull(extractionFactory) { "This DecoderClient has no extraction factory." }()

    /**
     * A fresh client bound to the isolated write instance (`:decoders:write`, M3.5, design section
     * 2.3 step 3), for one create operation: the caller runs its own [callTwoPipes] on it and then
     * [unbind]s it. A **third** dedicated instance and executor, distinct from both the browsing
     * pool ([STREAM_THREADS]) and the extraction client's own (`forExtraction`'s `Dispatchers.IO`)
     * -- sharing either would let a concurrent browse/extract and a compress starve each other's
     * liveness watchdog forever (the design's own review finding). Throws when this client was
     * built without a factory (a test client that never asked for one).
     */
    fun writer(): DecoderClient = checkNotNull(writerFactory) { "This DecoderClient has no writer factory." }()

    /**
     * Drops the current binding whatever its state -- a pending bind is cancelled, an in-flight
     * transaction is left to end with `DeadObjectException` and be logged once -- and unbinds, so the
     * platform can reap the process. The next call, if any, binds afresh. What the extractor calls
     * when an operation ends (section 2.3 step 8).
     */
    fun unbind() {
        val generation = synchronized(lock) { binding?.generation } ?: return
        dropConnection(generation)
    }

    /** One binding: its generation, and the deferred its `onServiceConnected` completes. */
    private inner class Binding(val generation: Long) : ServiceConnection {
        val service = CompletableDeferred<IDecoderService>()

        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service.complete(IDecoderService.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dropConnection(generation)
        }

        override fun onBindingDied(name: ComponentName?) {
            dropConnection(generation)
        }
    }

    private val lock = Any()

    /** The binding being established or in use, or `null` between connections. Guarded by [lock]. */
    private var binding: Binding? = null

    /** The generation of the newest binding ever created. Guarded by [lock]. */
    private var newestGeneration = 0L

    /** Calls in progress ([call] and [callStreaming]); the idle timer arms only at zero. Guarded by [lock]. */
    private var inFlight = 0

    /** The armed idle-unbind timer, if any. Guarded by [lock]. */
    private var idleTimer: Job? = null

    /**
     * Runs the Binder transactions and the drains: a dedicated fixed pool, so a fill waiting on a
     * thread cannot starve behind the app's other I/O and vice versa. Not a child of any caller's
     * scope, so a timed-out `await` abandons the transaction instead of waiting for it; a
     * [SupervisorJob], so an abandoned transaction that later fails cannot cancel the scope. The
     * threads are daemons: the pool is never shut down (the client is application-scoped).
     */
    private val transactions = CoroutineScope(
        (
            transactionDispatcher ?: Executors.newFixedThreadPool(STREAM_THREADS) { runnable ->
                Thread(runnable, "decoder-stream-${streamThreads.incrementAndGet()}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            ) + SupervisorJob(),
    )

    /** Idle timers, off the transaction pool so a saturated pool cannot delay an unbind. */
    private val timers = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Forgets the binding of generation [generation] -- and only that one -- cancelling a bind still
     * in flight, and unbinds. A no-op when that binding is already gone or a newer one has replaced
     * it, so a callback for a dead binding, a stale idle timer, and a caller noticing the same
     * death never double-unbind or unbind the wrong connection.
     */
    private fun dropConnection(generation: Long) {
        val dropped = synchronized(lock) {
            val current = binding ?: return
            if (current.generation != generation) return
            binding = null
            current
        }
        if (!dropped.service.isCompleted) dropped.service.cancel()
        runCatching { unbind(dropped) }
    }

    /** The idle timer's drop: the same guard plus "nothing in flight", so a timer that already woke
     * when a new call began cannot take that call's connection away. */
    private fun dropIfIdle(generation: Long) {
        synchronized(lock) { if (inFlight > 0) return }
        dropConnection(generation)
    }

    /** Connects (binding afresh if needed) and returns the service with its binding's generation. */
    private suspend fun ensureConnected(): Pair<IDecoderService, Long> {
        val (use, created) = synchronized(lock) {
            val existing = binding
            if (existing != null) {
                existing to false
            } else {
                Binding(++newestGeneration).also { binding = it } to true
            }
        }
        if (created) {
            val bound = try {
                bind(use)
            } catch (e: RuntimeException) {
                dropConnection(use.generation)
                throw e
            }
            if (!bound) {
                // A `bindService` that returned false still needs `unbindService` to release the
                // connection: exactly one, here.
                dropConnection(use.generation)
                throw RemoteException("DecoderService bind() returned false")
            }
        }
        return use.service.await() to use.generation
    }

    private fun beginCall() {
        synchronized(lock) {
            inFlight += 1
            idleTimer?.cancel()
            idleTimer = null
        }
    }

    private fun endCall() {
        val arm = synchronized(lock) {
            inFlight -= 1
            if (inFlight == 0) binding?.generation else null
        } ?: return
        if (idleUnbindMillis != NO_IDLE_UNBIND) armIdleTimer(arm)
    }

    private fun armIdleTimer(generation: Long) {
        synchronized(lock) {
            idleTimer?.cancel()
            idleTimer = timers.launch {
                delay(idleUnbindMillis)
                dropIfIdle(generation)
            }
        }
    }

    /** Whether the binding of [generation] is no longer the current one (someone else dropped it). */
    private fun wasDropped(generation: Long): Boolean = synchronized(lock) { binding?.generation != generation }

    /** `false` on any failure: no connection, a timeout, or a crash mid-call. */
    suspend fun ping(): Boolean = call { it.ping() }.valueOrNull() ?: false

    /** `null` on any failure -- the file is treated as unsafe to preview, per section 4.4. */
    suspend fun sniff(pfd: ParcelFileDescriptor): String? = call { it.sniff(pfd) }.valueOrNull()

    /**
     * One archive inspection in the decoder process (section 4.4's "structure" call, so the
     * default budget is [STRUCTURE_TIMEOUT_MILLIS], not the 5 s the two quick calls get).
     * [archive] must be a seekable, read-only descriptor -- `archive.ArchiveSource` is what
     * guarantees that -- and stays the caller's to close. [DecoderCall.Ok] carries the service's
     * answer, whose own `outcome` may still be an engine refusal; [DecoderCall.TimedOut] and
     * [DecoderCall.Failed] are the client's verdicts about the process.
     */
    suspend fun inspectArchive(
        archive: ParcelFileDescriptor,
        limits: ArchiveLimits,
        maxRows: Int = DEFAULT_MAX_ROWS,
        timeoutMillis: Long = STRUCTURE_TIMEOUT_MILLIS,
    ): DecoderCall<ArchiveInspection> = call(timeoutMillis) { it.inspectArchive(archive, limits, maxRows) }

    /** One attempt's outcome; [Failed.retryable] when another call's drop took the connection away first. */
    private sealed class Attempt<out T> {
        data class Ok<T>(val value: T) : Attempt<T>()
        data object TimedOut : Attempt<Nothing>()
        data class Failed(val retryable: Boolean) : Attempt<Nothing>()
    }

    /**
     * [DecoderCall.TimedOut] when the deadline passes (binding is inside it too, so a bind that
     * never connects also times out), [DecoderCall.Failed] on a [RemoteException], a failed bind,
     * a connection dropped from under the call, or anything else the transaction throws; every
     * failure drops the connection. A transaction that failed because another call's timeout had
     * already dropped its connection is retried once on a fresh binding. `T : Any` so that `null`
     * from [withTimeoutOrNull] can only mean the deadline passed.
     */
    private suspend fun <T : Any> call(
        timeoutMillis: Long = this.timeoutMillis,
        block: (IDecoderService) -> T,
    ): DecoderCall<T> {
        beginCall()
        try {
            var attempts = 0
            while (true) {
                attempts += 1
                when (val attempt = attemptOnce(timeoutMillis, block)) {
                    is Attempt.Ok -> return DecoderCall.Ok(attempt.value)
                    Attempt.TimedOut -> return DecoderCall.TimedOut
                    is Attempt.Failed -> if (!retryOnDrop || !attempt.retryable || attempts > 1) return DecoderCall.Failed
                }
            }
        } finally {
            endCall()
        }
    }

    private suspend fun <T : Any> attemptOnce(timeoutMillis: Long, block: (IDecoderService) -> T): Attempt<T> {
        var transaction: Deferred<T>? = null
        var generation = NO_GENERATION
        return try {
            val result = withTimeoutOrNull(timeoutMillis) {
                val (service, gen) = ensureConnected()
                generation = gen
                val started = transactions.async { block(service) }
                transaction = started
                started.await()
            }
            if (result != null) {
                Attempt.Ok(result)
            } else {
                transaction?.let(::abandon)
                dropConnection(generation)
                Attempt.TimedOut
            }
        } catch (e: CancellationException) {
            // The caller's own cancellation keeps its structured-concurrency meaning. Anything else
            // is our pending bind cancelled by `dropConnection()` (a disconnect or a binding death)
            // from under `ensureConnected` -- the connection is already dropped by then.
            currentCoroutineContext().ensureActive()
            Attempt.Failed(retryable = false)
        } catch (e: RemoteException) {
            val dropped = generation != NO_GENERATION && wasDropped(generation)
            dropConnection(generation)
            Attempt.Failed(retryable = dropped)
        } catch (e: RuntimeException) {
            // A `bindService` that throws (`SecurityException`), or a service-side exception AIDL
            // re-throws in the caller: the contract is a failure value, never a thrown exception.
            Log.w(TAG, "Decoder call failed with ${e.javaClass.simpleName}")
            dropConnection(generation)
            Attempt.Failed(retryable = false)
        }
    }

    /**
     * A call whose bulk result leaves `:decoders` through a pipe (M3.3, section 2.2). The client
     * creates the pipe; [block] runs the transaction with the service and the pipe's **write end**
     * (the service takes ownership of its Binder dup and closes it on return; the client closes its
     * own copy when the transaction returns or is abandoned, which is what gives the reader EOF);
     * [drain] runs concurrently on the client's pool and reads the **read end** until EOF, applying
     * the Robolectric-safe rule -- an end-of-stream is final only once the write end is closed and
     * the transaction has returned (file-backed test pipes report -1 before the first write; a real
     * pipe never takes the retry path). [archive] is the descriptor whose shared offset counts as
     * liveness; it stays the caller's to close.
     *
     * Result: [DecoderCall.Ok] with `block`'s value once both the transaction and the drain have
     * finished; [DecoderCall.TimedOut] when nothing moved for [inactivityMillis] (the read end is
     * closed, which wakes a blocked drain, and the connection is dropped); [DecoderCall.Failed] as
     * for [call]. A failure inside [drain] itself (the sink's disk is full) is **thrown** to the
     * caller after the transaction has been stopped -- it is not the decoder process's fault, so
     * the connection is kept. [drain] may run twice: a transaction dropped by another call's
     * timeout is retried once on a fresh binding with a fresh pipe, so the drain must start over
     * from an empty sink each time it is invoked.
     *
     * M3.4 (section 2.3 step 6) extends the liveness rule without changing it: [busy] is polled
     * each tick and pauses the inactivity count while true (the drain is inside a slow provider
     * `write`/`close` -- a cloud, an OTG drive, an SD card fsync -- which must not look like a hung
     * engine); [progress] is a third activity signal beside the sink bytes and the archive offset (a
     * demultiplexer's own count); [cancelled] is polled each tick too and, when true, closes the
     * read end (EPIPE for the engine), waits up to [drainFailureWaitMillis] for the transaction,
     * abandons it otherwise, and returns [DecoderCall.Failed] -- the caller that asked for the cancel
     * knows what that means. [drainFailureWaitMillis] also bounds the wait after the drain itself
     * fails (the extractor's cancel throws out of its drain).
     */
    suspend fun <T : Any> callStreaming(
        archive: ParcelFileDescriptor,
        inactivityMillis: Long = STREAM_INACTIVITY_MILLIS,
        drain: suspend (InputStream) -> Unit,
        busy: () -> Boolean = { false },
        progress: () -> Long = { 0L },
        cancelled: () -> Boolean = { false },
        drainFailureWaitMillis: Long = inactivityMillis,
        block: (IDecoderService, ParcelFileDescriptor) -> T,
    ): DecoderCall<T> {
        beginCall()
        try {
            var attempts = 0
            while (true) {
                attempts += 1
                when (val attempt = streamOnce(archive, inactivityMillis, drain, busy, progress, cancelled, drainFailureWaitMillis, block)) {
                    is Attempt.Ok -> return DecoderCall.Ok(attempt.value)
                    Attempt.TimedOut -> return DecoderCall.TimedOut
                    is Attempt.Failed -> if (!retryOnDrop || !attempt.retryable || attempts > 1) return DecoderCall.Failed
                }
            }
        } finally {
            endCall()
        }
    }

    private suspend fun <T : Any> streamOnce(
        archive: ParcelFileDescriptor,
        inactivityMillis: Long,
        drain: suspend (InputStream) -> Unit,
        busy: () -> Boolean,
        progress: () -> Long,
        cancelled: () -> Boolean,
        drainFailureWaitMillis: Long,
        block: (IDecoderService, ParcelFileDescriptor) -> T,
    ): Attempt<T> {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val transactionDone = AtomicBoolean(false)
        val drained = AtomicLong(0L)
        val stream = DrainStream(ParcelFileDescriptor.AutoCloseInputStream(readEnd), drained, transactionDone)
        var transaction: Deferred<T>? = null
        var drainJob: Deferred<Unit>? = null
        var generation = NO_GENERATION
        fun closePipe() {
            runCatching { stream.close() }
            runCatching { writeEnd.close() }
        }
        try {
            val connected = withTimeoutOrNull(inactivityMillis) { ensureConnected() }
            if (connected == null) {
                closePipe()
                dropConnection(generation)
                return Attempt.TimedOut
            }
            val (service, gen) = connected
            generation = gen
            // The drain takes its thread before the transaction is started, so a saturated pool can
            // never hold a transaction whose reader is still queued (the service would block on a
            // full pipe, and the transaction would hold its thread waiting for a reader that cannot
            // get one).
            val drainStarted = CompletableDeferred<Unit>()
            val startedDrain = transactions.async {
                drainStarted.complete(Unit)
                drain(stream)
            }
            drainJob = startedDrain
            drainStarted.await()
            val startedTransaction = transactions.async {
                try {
                    block(service, writeEnd)
                } finally {
                    runCatching { writeEnd.close() }
                    transactionDone.set(true)
                }
            }
            transaction = startedTransaction

            // Liveness: sink bytes, the archive's shared offset or the caller's own progress moving
            // within the budget; the count pauses while the caller says it is busy.
            var lastDrained = -1L
            var lastOffset: Long? = null
            var lastProgress = -1L
            var idleMillis = 0L
            var result: T? = null
            while (result == null) {
                result = withTimeoutOrNull(livenessPollMillis) { startedTransaction.await() }
                if (result != null) break
                if (startedDrain.isCompleted && startedDrain.getCompletionExceptionOrNull() != null) {
                    // The sink failed under the service (disk full, a closed file): stop the transaction
                    // by closing both pipe ends (the engine's write gets EPIPE and returns), then
                    // surface the drain's own failure.
                    closePipe()
                    withTimeoutOrNull(drainFailureWaitMillis) { runCatching { startedTransaction.await() } } ?: abandonAndDrop(startedTransaction, generation)
                    startedDrain.await()
                }
                if (cancelled()) {
                    // The caller withdrew: the read end goes first (the engine's next write gets EPIPE,
                    // and its header-pass poll sees the hang-up), the transaction gets a bounded wait,
                    // then it is abandoned and the binding dropped so the process can be reaped.
                    closePipe()
                    withTimeoutOrNull(drainFailureWaitMillis) { runCatching { startedTransaction.await() } } ?: abandon(startedTransaction)
                    dropConnection(generation)
                    startedDrain.cancel()
                    return Attempt.Failed(retryable = false)
                }
                val nowDrained = drained.get()
                val nowOffset = offsetProbe(archive)
                val nowProgress = progress()
                if (nowDrained != lastDrained || nowOffset != lastOffset || nowProgress != lastProgress) {
                    lastDrained = nowDrained
                    lastOffset = nowOffset
                    lastProgress = nowProgress
                    idleMillis = 0L
                } else if (!busy()) {
                    idleMillis += livenessPollMillis
                    if (idleMillis >= inactivityMillis) {
                        closePipe()
                        startedDrain.cancel()
                        abandonAndDrop(startedTransaction, generation)
                        return Attempt.TimedOut
                    }
                }
            }
            // The transaction returned and the write end is closed: the drain finishes on the
            // remaining bytes. It is the caller's own sink work now, so a stall here is its own
            // failure, not the process's -- the connection is kept either way.
            idleMillis = 0L
            while (!startedDrain.isCompleted) {
                if (withTimeoutOrNull(livenessPollMillis) { startedDrain.await() } != null) break
                val nowDrained = drained.get()
                val nowProgress = progress()
                if (nowDrained != lastDrained || nowProgress != lastProgress) {
                    lastDrained = nowDrained
                    lastProgress = nowProgress
                    idleMillis = 0L
                } else if (!busy()) {
                    idleMillis += livenessPollMillis
                    if (idleMillis >= inactivityMillis) {
                        closePipe()
                        startedDrain.cancel()
                        throw IOException("The listing sink stopped accepting bytes.")
                    }
                }
            }
            startedDrain.await()
            return Attempt.Ok(checkNotNull(result))
        } catch (e: CancellationException) {
            closePipe()
            drainJob?.cancel()
            transaction?.let(::abandon)
            currentCoroutineContext().ensureActive()
            return Attempt.Failed(retryable = false)
        } catch (e: RemoteException) {
            closePipe()
            drainJob?.cancel()
            val dropped = generation != NO_GENERATION && wasDropped(generation)
            dropConnection(generation)
            return Attempt.Failed(retryable = dropped)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Streaming decoder call failed with ${e.javaClass.simpleName}")
            closePipe()
            drainJob?.cancel()
            dropConnection(generation)
            return Attempt.Failed(retryable = false)
        } finally {
            closePipe()
        }
    }

    /**
     * A call whose bulk data flows through **two** pipes at once (M3.5, design section 2.3 step
     * 3): [feed] writes source bytes into the write end of the "in" pipe (the engine reads the
     * read end as `writeArchive`'s `input`); [drain] reads the archive bytes libarchive produces
     * from the read end of the "out" pipe (the engine writes the write end as `writeArchive`'s
     * `output`). Both run concurrently with the Binder transaction, each on this client's own
     * [transactions] pool -- for the create client returned by [writer], a dedicated executor
     * distinct from the browsing pool [callStreaming] uses and from the extraction client's own,
     * never shared with anything that can itself be "busy" waiting on `:decoders` (the deadlock
     * this design step exists to rule out: two independently-busy calls sharing one small pool
     * starve each other forever). Neither [feed] nor [drain] ever waits on the other; each closes
     * its own end when it finishes normally, and every exit path -- success, a cancel, a feeder or
     * drain failure, a timeout -- closes **all four** client-side pipe ends (its own copies of
     * both ends of both pipes) from this one function's own `finally`, never from a second thread,
     * so nothing races the Binder call's own descriptor duplication.
     *
     * Liveness pauses while [busy] is true and otherwise counts [inactivityMillis] of neither
     * [feedProgress] nor [drainProgress] moving. **No transparent retry** (design section 2.3 step
     * 3(d)): a create call that meets a dropped connection is the caller's failure to handle, never
     * silently reissued into what would be a second, interleaved `writeArchive` transaction.
     * [cancelled] is polled each tick and, when true, closes every pipe end (EPIPE for the engine's
     * next read or write) and returns [DecoderCall.Failed] after a bounded wait for the transaction.
     * A failure inside [feed]/[drain] itself is **thrown** to the caller once the transaction has
     * been stopped -- it is the caller's own source/destination failing, not the decoder process,
     * so the connection is kept.
     */
    suspend fun <T : Any> callTwoPipes(
        inactivityMillis: Long,
        feed: suspend (OutputStream) -> Unit,
        drain: suspend (InputStream) -> Unit,
        busy: () -> Boolean = { false },
        feedProgress: () -> Long = { 0L },
        drainProgress: () -> Long = { 0L },
        cancelled: () -> Boolean = { false },
        drainFailureWaitMillis: Long = inactivityMillis,
        block: (IDecoderService, ParcelFileDescriptor, ParcelFileDescriptor) -> T,
    ): DecoderCall<T> {
        beginCall()
        try {
            return twoPipesOnce(inactivityMillis, feed, drain, busy, feedProgress, drainProgress, cancelled, drainFailureWaitMillis, block)
        } finally {
            endCall()
        }
    }

    private suspend fun <T : Any> twoPipesOnce(
        inactivityMillis: Long,
        feed: suspend (OutputStream) -> Unit,
        drain: suspend (InputStream) -> Unit,
        busy: () -> Boolean,
        feedProgress: () -> Long,
        drainProgress: () -> Long,
        cancelled: () -> Boolean,
        drainFailureWaitMillis: Long,
        block: (IDecoderService, ParcelFileDescriptor, ParcelFileDescriptor) -> T,
    ): DecoderCall<T> {
        val inPipe = ParcelFileDescriptor.createPipe()
        val inRead = inPipe[0]
        val inWrite = inPipe[1]
        val outPipe = ParcelFileDescriptor.createPipe()
        val outRead = outPipe[0]
        val outWrite = outPipe[1]
        var generation = NO_GENERATION
        var feederJob: Deferred<Unit>? = null
        var drainJob: Deferred<Unit>? = null
        var transactionJob: Deferred<T>? = null
        fun closeAllPipeEnds() {
            runCatching { inRead.close() }
            runCatching { inWrite.close() }
            runCatching { outRead.close() }
            runCatching { outWrite.close() }
        }
        try {
            val connected = withTimeoutOrNull(inactivityMillis) { ensureConnected() }
            if (connected == null) {
                closeAllPipeEnds()
                return DecoderCall.TimedOut
            }
            val (service, gen) = connected
            generation = gen

            // The drain and the feeder each take their thread before the transaction is started
            // (streamOnce's own reasoning, doubled): neither may be left queued behind a saturated
            // pool while the transaction holds a thread waiting for either of them.
            //
            // outRead is wrapped exactly as streamOnce's own DrainStream wraps its one pipe: a
            // read that sees nothing yet retries until transactionDone, rather than treating "no
            // bytes this instant" as the stream's real end. This is not only a Robolectric-testing
            // concern (its own pipes report EOF before a concurrent writer's first byte, proven by
            // RobolectricPipeReadTest) -- on a real device, drain's genuine EOF can only ever come
            // once EVERY copy of outWrite is closed, and this app's own copy is not closed until
            // the transaction below actually finishes; without DrainStream's retry, a read that
            // happens to run before the engine has written anything would see the same false EOF
            // and drain would quietly give up having read nothing at all.
            val transactionDone = AtomicBoolean(false)
            val drainStarted = CompletableDeferred<Unit>()
            val startedDrain = transactions.async {
                drainStarted.complete(Unit)
                DrainStream(ParcelFileDescriptor.AutoCloseInputStream(outRead), AtomicLong(0L), transactionDone).use { stream -> drain(stream) }
            }
            drainJob = startedDrain
            drainStarted.await()

            val feedStarted = CompletableDeferred<Unit>()
            val startedFeeder = transactions.async {
                feedStarted.complete(Unit)
                ParcelFileDescriptor.AutoCloseOutputStream(inWrite).use { stream -> feed(stream) }
            }
            feederJob = startedFeeder
            feedStarted.await()

            val startedTransaction = transactions.async {
                try {
                    block(service, inRead, outWrite)
                } finally {
                    // This process's OWN copies of the two ends the engine was handed, relinquished
                    // the instant the transaction itself is done -- not left for closeAllPipeEnds'
                    // later, single closing pass, which only runs once drain/feed have already
                    // settled (see the note above: that would be too late for outRead's real EOF).
                    runCatching { inRead.close() }
                    runCatching { outWrite.close() }
                    transactionDone.set(true)
                }
            }
            transactionJob = startedTransaction

            var lastFeed = -1L
            var lastDrain = -1L
            var idleMillis = 0L
            var result: T? = null
            while (result == null) {
                result = withTimeoutOrNull(livenessPollMillis) { startedTransaction.await() }
                if (result != null) break
                if (cancelled()) {
                    closeAllPipeEnds()
                    withTimeoutOrNull(drainFailureWaitMillis) { runCatching { startedTransaction.await() } } ?: abandon(startedTransaction)
                    startedFeeder.cancel()
                    startedDrain.cancel()
                    dropConnection(generation)
                    return DecoderCall.Failed
                }
                val feederFailure = startedFeeder.takeIf { it.isCompleted }?.getCompletionExceptionOrNull()
                val drainFailure = startedDrain.takeIf { it.isCompleted }?.getCompletionExceptionOrNull()
                if (feederFailure != null || drainFailure != null) {
                    // The app's own source read or destination write failed, not the engine: stop
                    // the transaction (both pipes closed, so the engine's next read/write gets
                    // EPIPE), bound the wait, then surface whichever side actually failed.
                    closeAllPipeEnds()
                    withTimeoutOrNull(drainFailureWaitMillis) { runCatching { startedTransaction.await() } } ?: abandon(startedTransaction)
                    startedFeeder.cancel()
                    startedDrain.cancel()
                    throw (feederFailure ?: drainFailure)!!
                }
                val nowFeed = feedProgress()
                val nowDrain = drainProgress()
                if (nowFeed != lastFeed || nowDrain != lastDrain) {
                    lastFeed = nowFeed
                    lastDrain = nowDrain
                    idleMillis = 0L
                } else if (!busy()) {
                    idleMillis += livenessPollMillis
                    if (idleMillis >= inactivityMillis) {
                        closeAllPipeEnds()
                        startedFeeder.cancel()
                        startedDrain.cancel()
                        abandonAndDrop(startedTransaction, generation)
                        return DecoderCall.TimedOut
                    }
                }
            }
            // The transaction returned: the feeder and drain finish on whatever remains (the
            // engine's own EOF/close), the caller's own work now -- a stall here is its own
            // failure, not the process's, so the connection is kept either way.
            idleMillis = 0L
            while (!startedFeeder.isCompleted || !startedDrain.isCompleted) {
                val settled = withTimeoutOrNull(livenessPollMillis) {
                    runCatching { startedFeeder.await() }
                    runCatching { startedDrain.await() }
                }
                if (settled != null) break
                val nowFeed = feedProgress()
                val nowDrain = drainProgress()
                if (nowFeed != lastFeed || nowDrain != lastDrain) {
                    lastFeed = nowFeed
                    lastDrain = nowDrain
                    idleMillis = 0L
                } else if (!busy()) {
                    idleMillis += livenessPollMillis
                    if (idleMillis >= inactivityMillis) {
                        closeAllPipeEnds()
                        startedFeeder.cancel()
                        startedDrain.cancel()
                        throw IOException("The create feeder/drain stopped accepting bytes.")
                    }
                }
            }
            startedFeeder.await()
            startedDrain.await()
            return DecoderCall.Ok(checkNotNull(result))
        } catch (e: CancellationException) {
            closeAllPipeEnds()
            feederJob?.cancel()
            drainJob?.cancel()
            transactionJob?.let(::abandon)
            currentCoroutineContext().ensureActive()
            return DecoderCall.Failed
        } catch (e: RemoteException) {
            closeAllPipeEnds()
            feederJob?.cancel()
            drainJob?.cancel()
            dropConnection(generation)
            return DecoderCall.Failed
        } catch (e: IOException) {
            // Re-thrown from a feeder/drain failure above: the caller's own source/destination,
            // not the decoder process -- the connection is kept.
            closeAllPipeEnds()
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Two-pipe decoder call failed with ${e.javaClass.simpleName}")
            closeAllPipeEnds()
            feederJob?.cancel()
            drainJob?.cancel()
            dropConnection(generation)
            return DecoderCall.Failed
        } finally {
            closeAllPipeEnds()
        }
    }

    private fun abandonAndDrop(transaction: Deferred<*>, generation: Long) {
        abandon(transaction)
        dropConnection(generation)
    }

    private fun <T> DecoderCall<T>.valueOrNull(): T? = (this as? DecoderCall.Ok<T>)?.value

    /** The caller has stopped waiting; whatever the transaction eventually does is logged once, never thrown. */
    private fun abandon(transaction: Deferred<*>) {
        transaction.invokeOnCompletion { cause ->
            val ending = if (cause == null) "returned" else "ended with ${cause.javaClass.simpleName}"
            Log.w(TAG, "Abandoned decoder call $ending after its timeout")
        }
    }

    /**
     * The read end of a streaming call's pipe, counting the bytes handed to the drain and applying
     * the drain rule: `-1` from the underlying stream is final only once the transaction has
     * returned (with the write end closed) **and** one further read after that also returns `-1`
     * -- the second read covers bytes written between an early `-1` and the transaction's return
     * on a file-backed test pipe. An earlier `-1` sleeps [DRAIN_RETRY_MILLIS] and retries. A real
     * pipe's `read` blocks until data or EOF, so it never sees the retry path.
     */
    private class DrainStream(
        private val inner: InputStream,
        private val drained: AtomicLong,
        private val transactionDone: AtomicBoolean,
    ) : InputStream() {
        private var doneSeen = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val n = inner.read(b, off, len)
                if (n > 0) {
                    drained.addAndGet(n.toLong())
                    return n
                }
                if (doneSeen) return -1
                if (transactionDone.get()) {
                    doneSeen = true
                    continue
                }
                Thread.sleep(DRAIN_RETRY_MILLIS)
            }
        }

        override fun close() = inner.close()
    }

    companion object {
        private const val TAG = "DecoderClient"
        private const val NO_GENERATION = -1L
        private val streamThreads = AtomicInteger()

        /** The budget for the two quick calls, `ping` and `sniff`. */
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        /** MASTER_PLAN section 4.4's "structure" budget: one archive header pass. */
        const val STRUCTURE_TIMEOUT_MILLIS = 30_000L

        /** How long a streaming call may go with neither sink bytes nor archive offset moving. */
        const val STREAM_INACTIVITY_MILLIS = 30_000L

        /** How long the binding stays up with nothing in flight (section 2.9). */
        const val IDLE_UNBIND_MILLIS = 60_000L

        /** An `idleUnbindMillis` that never arms the idle timer: the binding lives until [unbind]. */
        const val NO_IDLE_UNBIND = Long.MAX_VALUE

        /** The `instanceName` of the isolated extraction process: `:decoders:extract` (M3.4). */
        const val EXTRACTION_INSTANCE = "extract"

        /** How long an extraction waits for the transaction to return after a cancel or a drain failure. */
        const val EXTRACTION_CANCEL_WAIT_MILLIS = 5_000L

        /** `bindIsolatedService`'s callback executor; the callbacks only complete a deferred. */
        private val connectionExecutor: java.util.concurrent.Executor by lazy {
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "decoder-extract-connection").apply { isDaemon = true } }
        }

        /**
         * The client of the isolated extraction instance (M3.4, section 2.3 step 3): a bind with
         * `bindIsolatedService(..., "extract", ...)`, no idle timer, no transparent retry,
         * transactions on `Dispatchers.IO`. One per extraction operation; the extractor unbinds it.
         */
        fun forExtraction(context: Context): DecoderClient = DecoderClient(
            bind = { connection ->
                context.bindIsolatedService(
                    Intent(context, DecoderService::class.java),
                    Context.BIND_AUTO_CREATE,
                    EXTRACTION_INSTANCE,
                    connectionExecutor,
                    connection,
                )
            },
            unbind = { connection -> context.unbindService(connection) },
            idleUnbindMillis = NO_IDLE_UNBIND,
            transactionDispatcher = Dispatchers.IO,
            retryOnDrop = false,
        )

        /** The `instanceName` of the isolated write process: `:decoders:write` (M3.5). */
        const val WRITE_INSTANCE = "write"

        /** How long a create waits for the transaction to return after a cancel or a feeder/drain failure. */
        const val CREATE_CANCEL_WAIT_MILLIS = 5_000L

        /** The feeder/drain/transaction pool's own size (design section 2.3 step 3): exactly the
         * three concurrent roles one create needs, plus headroom -- matches [STREAM_THREADS]'s
         * own sizing rationale, on a pool this client never shares with browsing or extraction. */
        const val CREATE_THREADS = 4

        private val createThreads = AtomicInteger()

        /** `bindIsolatedService`'s callback executor for the write instance; the callbacks only
         * complete a deferred, same as [connectionExecutor]. */
        private val createConnectionExecutor: java.util.concurrent.Executor by lazy {
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "decoder-write-connection").apply { isDaemon = true } }
        }

        /** The feeder/drain/transaction pool itself, created once and reused by every write client
         * this process makes -- a fresh [Executors.newFixedThreadPool] per operation would still be
         * correct but wastefully re-spins [CREATE_THREADS] daemon threads on every compress. */
        private val createTransactionDispatcher: CoroutineDispatcher by lazy {
            Executors.newFixedThreadPool(CREATE_THREADS) { runnable ->
                Thread(runnable, "decoder-create-${createThreads.incrementAndGet()}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }

        /**
         * The client of the isolated write instance (M3.5, design section 2.3 step 3): a bind
         * with `bindIsolatedService(..., "write", ...)`, no idle timer, no transparent retry,
         * transactions on [createTransactionDispatcher] -- a **third**, dedicated pool distinct
         * from both the browsing pool ([STREAM_THREADS]) and [forExtraction]'s own `Dispatchers.IO`
         * use, so a concurrent browse fill, a running extraction, and a compress's own feeder/
         * drain/transaction can never starve one another's liveness watchdog. One per create
         * operation; the creator unbinds it when the operation ends.
         */
        fun forWriter(context: Context): DecoderClient = DecoderClient(
            bind = { connection ->
                context.bindIsolatedService(
                    Intent(context, DecoderService::class.java),
                    Context.BIND_AUTO_CREATE,
                    WRITE_INSTANCE,
                    createConnectionExecutor,
                    connection,
                )
            },
            unbind = { connection -> context.unbindService(connection) },
            idleUnbindMillis = NO_IDLE_UNBIND,
            transactionDispatcher = createTransactionDispatcher,
            retryOnDrop = false,
        )

        /** How often a streaming call samples its two liveness signals. */
        const val LIVENESS_POLL_MILLIS = 1_000L

        /** The transaction-and-drain pool: two concurrent fills plus a listing, each a pair. */
        const val STREAM_THREADS = 4

        /** How many listing rows an inspection carries by default (about 50 KB of Parcel). */
        const val DEFAULT_MAX_ROWS = 500

        private const val DRAIN_RETRY_MILLIS = 10L

        /** `lseek(fd, 0, SEEK_CUR)` on the caller's copy of a descriptor: the offset is shared with
         * every dup, the Binder one included, so it moves as the engine reads. `null` when the
         * platform cannot answer (a test runtime, a closed descriptor). */
        fun sharedOffset(pfd: ParcelFileDescriptor): Long? =
            runCatching { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_CUR) }.getOrNull()
    }
}
