package io.github.mbaliga.fylz.decoder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The client half of the [DecoderService] boundary (docs/agent/MASTER_PLAN.md section 4.4):
 * binds lazily, enforces a per-call timeout, and treats a timeout or a crash the same way --
 * drop the connection and rebind fresh on the next call, never propagate either as a thrown
 * exception. `ping`/`sniff` return a failure value (`false`/`null`) instead, matching "a crash
 * marks that file unsafe to preview; it never crashes the app."
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

    private suspend fun <T> call(block: (IDecoderService) -> T): T? {
        return try {
            withTimeoutOrNull(timeoutMillis) {
                val service = ensureConnected()
                withContext(Dispatchers.IO) { block(service) }
            } ?: run { dropConnection(); null }
        } catch (e: RemoteException) {
            dropConnection()
            null
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
