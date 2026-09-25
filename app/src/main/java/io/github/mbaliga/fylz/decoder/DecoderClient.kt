package io.github.mbaliga.fylz.decoder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * binds lazily, enforces a per-call timeout, and treats a timeout or a crash the same way --
 * drop the connection and rebind fresh on the next call, never propagate either as a thrown
 * exception. `ping`/`sniff` return a failure value (`false`/`null`) instead, matching "a crash
 * marks that file unsafe to preview; it never crashes the app"; [inspectArchive] returns a
 * [DecoderCall] so its caller can distinguish the two.
 *
 * What the timeout really does. Each Binder transaction runs in a job owned by the client's own
 * [transactions] scope, deliberately *not* a child of the caller's coroutine, and the timeout
 * wraps only the wait for that job. So when the deadline passes the caller gets its failure
 * value at once and the client unbinds: the transaction is abandoned on the calling side, not
 * completed, and losing the last binding is what lets the platform reap the isolated
 * `:decoders` process. Nothing here can interrupt the Binder thread over there -- it keeps
 * running the native call until the process is reaped -- and the abandoned job stays parked
 * on the transaction until it returns, normally with a [android.os.DeadObjectException] once
 * the process is gone, which the job swallows with a single warning log. (The structured
 * alternative, `withTimeout { withContext(IO) { … } }`, waits for its blocking body and only
 * reports the timeout after the native call has returned -- the caller would be frozen for as
 * long as the hang, and the unbind would never happen while the process was actually hung.)
 *
 * Threads. [pending] is written by the calling coroutine (whatever thread it runs on) and by the
 * platform's [ServiceConnection] callbacks on the main thread, so it is an [AtomicReference]
 * claimed with compare-and-set, never a plain field. A [dropConnection] that races an in-flight
 * [ensureConnected] (a binding dies while a call is still waiting to connect) cancels the
 * pending deferred; [call] turns that into [DecoderCall.Failed], and only the caller's *own*
 * cancellation propagates out as a `CancellationException`.
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
) {
    constructor(context: Context, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) : this(
        bind = { connection ->
            context.bindService(Intent(context, DecoderService::class.java), connection, Context.BIND_AUTO_CREATE)
        },
        unbind = { connection -> context.unbindService(connection) },
        timeoutMillis = timeoutMillis,
    )

    /** The connection being established or in use, or `null` between connections. See the class doc. */
    private val pending = AtomicReference<CompletableDeferred<IDecoderService>?>(null)

    /**
     * Runs the Binder transactions. Not a child of any caller's scope, so a timed-out `await`
     * abandons the transaction instead of waiting for it. A [SupervisorJob], so an abandoned
     * transaction that later fails (the usual [android.os.DeadObjectException]) cannot cancel the
     * scope and with it every later call. [Dispatchers.IO] because an abandoned call holds its
     * thread until the transaction returns, which is what that elastic pool is for.
     */
    private val transactions = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            pending.get()?.complete(IDecoderService.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dropConnection()
        }

        override fun onBindingDied(name: ComponentName?) {
            dropConnection()
        }
    }

    /** Forgets the pending connection, cancelling a bind still in flight, and unbinds. A no-op when
     * nothing is pending, so a callback and a caller noticing the same death do not double-unbind. */
    private fun dropConnection() {
        val dropped = pending.getAndSet(null) ?: return
        if (!dropped.isCompleted) dropped.cancel()
        runCatching { unbind(connection) }
    }

    private suspend fun ensureConnected(): IDecoderService {
        while (true) {
            val existing = pending.get()
            if (existing != null && !existing.isCancelled) return existing.await()
            val deferred = CompletableDeferred<IDecoderService>()
            // Another caller (or a callback) got there first: re-read and use theirs.
            if (!pending.compareAndSet(existing, deferred)) continue
            if (!bind(connection)) {
                // `pending` keeps this deferred so [call]'s `dropConnection()` cancels it and
                // unbinds: a `bindService` that returned false still needs `unbindService` to
                // release the connection.
                throw RemoteException("DecoderService bind() returned false")
            }
            return deferred.await()
        }
    }

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

    /**
     * [DecoderCall.TimedOut] when the deadline passes (binding is inside it too, so a bind that
     * never connects also times out), [DecoderCall.Failed] on a [RemoteException], a failed bind,
     * a connection dropped from under the call, or anything else the transaction throws; every
     * failure drops the connection. `T : Any` so that `null` from [withTimeoutOrNull] can only
     * mean the deadline passed.
     */
    private suspend fun <T : Any> call(
        timeoutMillis: Long = this.timeoutMillis,
        block: (IDecoderService) -> T,
    ): DecoderCall<T> {
        var transaction: Deferred<T>? = null
        return try {
            val result = withTimeoutOrNull(timeoutMillis) {
                val service = ensureConnected()
                val started = transactions.async { block(service) }
                transaction = started
                started.await()
            }
            if (result != null) {
                DecoderCall.Ok(result)
            } else {
                transaction?.let(::abandon)
                dropConnection()
                DecoderCall.TimedOut
            }
        } catch (e: CancellationException) {
            // The caller's own cancellation keeps its structured-concurrency meaning. Anything else
            // is our pending bind cancelled by `dropConnection()` (a disconnect or a binding death)
            // from under `ensureConnected` -- the connection is already dropped by then.
            currentCoroutineContext().ensureActive()
            DecoderCall.Failed
        } catch (e: RemoteException) {
            dropConnection()
            DecoderCall.Failed
        } catch (e: RuntimeException) {
            // A `bindService` that throws (`SecurityException`), or a service-side exception AIDL
            // re-throws in the caller: the contract is a failure value, never a thrown exception.
            Log.w(TAG, "Decoder call failed with ${e.javaClass.simpleName}")
            dropConnection()
            DecoderCall.Failed
        }
    }

    private fun <T> DecoderCall<T>.valueOrNull(): T? = (this as? DecoderCall.Ok<T>)?.value

    /** The caller has stopped waiting; whatever the transaction eventually does is logged once, never thrown. */
    private fun abandon(transaction: Deferred<*>) {
        transaction.invokeOnCompletion { cause ->
            val ending = if (cause == null) "returned" else "ended with ${cause.javaClass.simpleName}"
            Log.w(TAG, "Abandoned decoder call $ending after its timeout")
        }
    }

    companion object {
        private const val TAG = "DecoderClient"

        /** The budget for the two quick calls, `ping` and `sniff`. */
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        /** MASTER_PLAN section 4.4's "structure" budget: one archive header pass. */
        const val STRUCTURE_TIMEOUT_MILLIS = 30_000L

        /** How many listing rows an inspection carries by default (about 50 KB of Parcel). */
        const val DEFAULT_MAX_ROWS = 500
    }
}
