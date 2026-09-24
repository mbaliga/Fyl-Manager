package io.github.mbaliga.fylz.decoder

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.mbaliga.fylz.core.FylzCore
import kotlinx.coroutines.runBlocking

/**
 * The isolated decoder process (docs/agent/MASTER_PLAN.md section 4.4): parsing untrusted files
 * with native code happens here, in a separate, `android:isolatedProcess="true"` process, never
 * in the UI process. A crash here marks that one file unsafe to preview; it must never take the
 * host app down. [DecoderClient] is the only caller and owns the timeout/kill/restart contract
 * this process is designed to be disposable under.
 *
 * `sniff` delegates to [FylzCore.sniffFile], backed by the `fylz-sniff` crate's real content
 * detection (M2.5) -- this service needed no change when that crate's stub body was replaced.
 * `runBlocking` is deliberate: AIDL calls run on a Binder thread-pool thread with no caller
 * waiting on anything else, so blocking it for the length of one native call costs nothing a
 * coroutine would save.
 */
class DecoderService : Service() {

    private val binder = object : IDecoderService.Stub() {
        override fun ping(): Boolean = true

        override fun sniff(pfd: ParcelFileDescriptor): String {
            pfd.use { open ->
                return runBlocking { FylzCore.sniffFile(open.fd) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
