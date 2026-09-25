package io.github.mbaliga.fylz.decoder

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.mbaliga.fylz.core.ArchiveInspectionRecord
import io.github.mbaliga.fylz.core.ArchiveLimitsRecord
import io.github.mbaliga.fylz.core.FylzCore
import kotlinx.coroutines.runBlocking

/** The engine call behind [DecoderService.inspectArchive]: a raw fd, the limits, the row cap. */
typealias ArchiveEngine = (fd: Int, limits: ArchiveLimitsRecord, maxRows: Int) -> ArchiveInspectionRecord

/**
 * The isolated decoder process (docs/agent/MASTER_PLAN.md section 4.4): parsing untrusted files
 * with native code happens here, in a separate, `android:isolatedProcess="true"` process, never
 * in the UI process. A crash here marks that one file unsafe to preview; it must never take the
 * host app down. [DecoderClient] is the only caller and owns the timeout/kill/restart contract
 * this process is designed to be disposable under.
 *
 * `sniff` delegates to [FylzCore.sniffFile], backed by the `fylz-sniff` crate's real content
 * detection (M2.5). Its `runBlocking` is deliberate: AIDL calls run on a Binder thread-pool
 * thread with no caller waiting on anything else, so blocking it for the length of one native
 * call costs nothing a coroutine would save. (`inspectArchive` needs none, because the archive
 * engine's uniffi function is synchronous -- a `suspend` function that never awaits is a pattern
 * not to copy.)
 *
 * `inspectArchive` (M3.2) reads the archive through the caller's descriptor with `fylz-archive`
 * and answers with an [ArchiveInspection] whose `outcome` carries the engine's verdict as data:
 * nothing is ever thrown across Binder from here. The engine is a constructor-injected [engine]
 * lambda, defaulting to the real [FylzCore.inspectArchive], so the *mapping* -- every
 * `ArchiveEngineException` subclass to its outcome, any other `Throwable` to `OUTCOME_INTERNAL`,
 * the record-to-Parcelable copy -- is unit-tested on the JVM without a native library
 * (`DecoderServiceMappingTest`). Android instantiates the service through the no-argument
 * constructor Kotlin generates for the all-defaults primary one.
 */
class DecoderService(private val engine: ArchiveEngine = FylzCore::inspectArchive) : Service() {

    private val binder = object : IDecoderService.Stub() {
        override fun ping(): Boolean = true

        override fun sniff(pfd: ParcelFileDescriptor): String {
            pfd.use { open ->
                return runBlocking { FylzCore.sniffFile(open.fd) }
            }
        }

        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection =
            this@DecoderService.inspectArchive(archive, limits, maxRows)
    }

    /**
     * The Binder method's body, on the service itself so a test can call it directly. `use`
     * closes this process's dup of the descriptor when the engine returns, whatever the outcome;
     * the caller's own descriptor is untouched. The fd crosses into Rust as a plain integer and
     * is never wrapped in anything that would close it.
     */
    internal fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection =
        archive.use { open ->
            try {
                engine(open.fd, limits.toRecord(), maxRows).toInspection()
            } catch (failure: Throwable) {
                failure.toFailedInspection()
            }
        }

    override fun onBind(intent: Intent?): IBinder = binder
}
