package io.github.mbaliga.fylz.operations

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The extraction notification's Cancel (M3.4, design section 2.3 step 8): sets the plan row's
 * `cancel_requested` flag, which the running extractor polls between frames and on its liveness
 * timer -- never `WorkManager.cancelWorkById`, which would cancel every transfer queued behind the
 * extraction in the unique chain. Not exported: only this app's own `PendingIntent` targets it.
 */
class ExtractCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)?.takeIf { it.isNotBlank() } ?: return
        // Off the main thread when the system dispatched us (goAsync); inline when invoked directly.
        val pending = runCatching { goAsync() }.getOrNull()
        if (pending == null) {
            OperationJournal(context.applicationContext).setCancelRequested(operationId)
            return
        }
        Thread {
            try {
                OperationJournal(context.applicationContext).setCancelRequested(operationId)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_CANCEL = "io.github.mbaliga.fylz.action.CANCEL_EXTRACT"
        const val EXTRA_OPERATION_ID = "operation_id"

        fun intent(context: Context, operationId: String): Intent =
            Intent(context, ExtractCancelReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_OPERATION_ID, operationId)
    }
}
