package io.github.mbaliga.fylz.operations

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The compress notification's Cancel (M3.5, design section 2.3 step 8): sets the plan row's
 * `cancel_requested` flag, which the running creator polls between frames -- never
 * `WorkManager.cancelWorkById`, which would cancel every transfer queued behind it in the unique
 * chain. Mirrors [ExtractCancelReceiver] exactly, as a separate receiver rather than a generalised
 * one: the two plan kinds live in different tables, and a wrong-table update would silently do
 * nothing rather than fail loudly. Not exported: only this app's own `PendingIntent` targets it.
 */
class CreateCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)?.takeIf { it.isNotBlank() } ?: return
        val pending = runCatching { goAsync() }.getOrNull()
        if (pending == null) {
            OperationJournal(context.applicationContext).setCreateCancelRequested(operationId)
            return
        }
        Thread {
            try {
                OperationJournal(context.applicationContext).setCreateCancelRequested(operationId)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_CANCEL = "io.github.mbaliga.fylz.action.CANCEL_CREATE"
        const val EXTRA_OPERATION_ID = "operation_id"

        fun intent(context: Context, operationId: String): Intent =
            Intent(context, CreateCancelReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_OPERATION_ID, operationId)
    }
}
