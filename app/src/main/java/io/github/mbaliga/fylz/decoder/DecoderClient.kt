package io.github.mbaliga.fylz.decoder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The client half of the [DecoderService] boundary (docs/agent/MASTER_PLAN.md section 4.4):
 * binds lazily, enforces a per-call timeout, and treats a timeout or a crash the same way --
 * drop the connection and rebind fresh on the next call, never propagate either as a thrown
 * exception. `ping`/`sniff` return a failure value (`false`/`null`) instead, matching "a crash
 * marks that file unsafe to preview; it never crashes the app."
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
 * [bind]/[unbind] are the only seam onto real Android IPC, so tests can drive the exact
 * [ServiceConnection] callbacks (a hang, a disconnect) without a real isolated process --
 * genuine cross-process kill-on-timeout and crash-recovery behaviour is a device-only concern,
 * covered in docs/agent/DEVICE_CHECKS.md rather than here.
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

    private var pending: CompletableDeferred<IDecoderService>? = null

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
            pending?.complete(IDecoderService.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dropConnection()
        }

        override fun onBindingDied(name: ComponentName?) {
            dropConnection()
        }
    }

    private fun dropConnection() {
        pending?.let { if (!it.isCompleted) it.cancel() }
        pending = null
        runCatching { unbind(connection) }
    }

    private suspend fun ensureConnected(): IDecoderService {
        pending?.let { existing -> if (!existing.isCancelled) return existing.await() }
        val deferred = CompletableDeferred<IDecoderService>()
        pending = deferred
        if (!bind(connection)) {
            pending = null
            deferred.cancel()
            throw RemoteException("DecoderService bind() returned false")
        }
        return deferred.await()
    }

    /** `false` on any failure: no connection, a timeout, or a crash mid-call. */
    suspend fun ping(): Boolean = call { it.ping() } ?: false

    /** `null` on any failure -- the file is treated as unsafe to preview, per section 4.4. */
    suspend fun sniff(pfd: ParcelFileDescriptor): String? = call { it.sniff(pfd) }

    /**
     * `null` on a timeout or a [RemoteException]; either drops the connection. Binding is inside
     * the timeout too, so a bind that never connects also times out. `T : Any` so that `null`
     * from [withTimeoutOrNull] can only mean the deadline passed.
     */
    private suspend fun <T : Any> call(
        timeoutMillis: Long = this.timeoutMillis,
        block: (IDecoderService) -> T,
    ): T? {
        var transaction: Deferred<T>? = null
        return try {
            withTimeoutOrNull(timeoutMillis) {
                val service = ensureConnected()
                val started = transactions.async { block(service) }
                transaction = started
                started.await()
            } ?: run {
                transaction?.let(::abandon)
                dropConnection()
                null
            }
        } catch (e: RemoteException) {
            dropConnection()
            null
        }
    }

    /** The caller has stopped waiting; whatever the transaction eventually does is logged once, never thrown. */
    private fun abandon(transaction: Deferred<*>) {
        transaction.invokeOnCompletion { cause ->
            val ending = if (cause == null) "returned" else "ended with ${cause.javaClass.simpleName}"
            Log.w(TAG, "Abandoned decoder call $ending after its timeout")
        }
    }

    private companion object {
        const val TAG = "DecoderClient"
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
