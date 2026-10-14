package io.github.mbaliga.fylz

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery
import io.github.mbaliga.fylz.archive.ArchiveCacheSweeper
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveEncodingOverrides
import io.github.mbaliga.fylz.archive.ArchiveEntryCache
import io.github.mbaliga.fylz.archive.ArchiveInspector
import io.github.mbaliga.fylz.archive.ArchivePasswordSession
import io.github.mbaliga.fylz.archive.ArchiveSource
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.DecoderClient
import io.github.mbaliga.fylz.operations.ArchiveTester
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationRunner
import io.github.mbaliga.fylz.operations.WorkLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Installs the shared Hyle-constellation crash-recovery handler (dev.aarso:crash-recovery,
 * wired via the hyle-design-system submodule + includeBuild). A crash is captured to an
 * app-private file only; nothing is ever transmitted anywhere. [MainActivity] checks for a
 * pending report first thing in `onCreate` and shows the recovery screen instead of its
 * normal content when one exists.
 */
class FylzApplication : Application() {

    /** Outlives every Activity/composable (P0.5, A3): a copy, move, extract, recycle or PDF job
     * launched through [operationRunner] keeps running across rotation, folding or resizing
     * instead of dying with whatever `rememberCoroutineScope()` started it. */
    val operationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val operationRunner: OperationRunner by lazy { OperationRunner(operationScope, this) }

    /** The app's one set of archive limits (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.2). */
    val archiveLimits: ArchiveLimits = ArchiveLimits()

    /**
     * The one client of the isolated decoder process, application-scoped like [operationRunner]
     * and for the same reason: `DecoderClient` owns a `ServiceConnection` that nothing
     * composition-scoped could release, and the binding (and so `:decoders`) should live for a
     * browsing session rather than per call. Bound with the application context. Since M3.3 it
     * unbinds after 60 s with nothing in flight (`DecoderClient.IDLE_UNBIND_MILLIS`) and rebinds on
     * the next call.
     */
    val decoderClient: DecoderClient by lazy { DecoderClient(this) }

    /** Keeps the archive caches (`archive-work/`, `archive-listings/`, `archive-entries/`) within budget (M3.3). */
    val archiveCacheSweeper: ArchiveCacheSweeper by lazy { ArchiveCacheSweeper(this) }

    /**
     * Archive inspection through the isolated decoder process (M3.2). Reached as
     * `(context.applicationContext as FylzApplication).archiveInspector`.
     */
    val archiveInspector: ArchiveInspector by lazy {
        ArchiveInspector(ArchiveSource(this, archiveLimits), decoderClient, archiveLimits, sweeper = archiveCacheSweeper)
    }

    /** Materialised archive entries, served as regular-file descriptors (M3.3, section 2.4). */
    val archiveEntryCache: ArchiveEntryCache by lazy { ArchiveEntryCache(this, decoderClient, archiveLimits, archiveCacheSweeper) }

    /**
     * Archive listings as browsable trees (M3.3, section 2.3): what `ArchiveDocumentsProvider`
     * and the archive preview open; one listing per archive, on disk and in memory.
     */
    val archiveCatalog: ArchiveCatalog by lazy {
        ArchiveCatalog(this, ArchiveSource(this, archiveLimits), decoderClient, archiveLimits, archiveEntryCache, archiveCacheSweeper)
    }

    /** The session-only manual charset override for legacy ZIP filenames (M3.7); never persisted. */
    val archiveEncodingOverrides: ArchiveEncodingOverrides by lazy { ArchiveEncodingOverrides() }

    /** The session-only, opt-in remembered archive passwords (M3.9); never persisted, never logged. */
    val archivePasswordSession: ArchivePasswordSession by lazy { ArchivePasswordSession() }

    /**
     * "Test archive" (M3.8): verifies every entry's CRC without extracting, on the isolated
     * extraction instance -- never the browsing one, same rule [archiveCatalog]'s own listings and
     * a real extraction both follow.
     */
    val archiveTester: ArchiveTester by lazy { ArchiveTester(archiveCatalog, extractionClient = decoderClient::extraction) }

    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this, appLabel = "Fylz")

        // Constructing this here, first, runs OperationJournal's own construction-time recovery
        // (RUNNING/PREFLIGHT/PAUSED -> NEEDS_ATTENTION/"PROCESS_INTERRUPTED") before anything else
        // in the process gets a chance to construct a journal of its own -- OperationRunner.recover
        // (P0.6) depends on that having already happened.
        // M3.4: EXTRACT rows are reconciled against WorkManager (a live worker is left to re-claim).
        val journal = OperationJournal(this)
        operationScope.launch { OperationRunner.recover(journal, contentResolver, WorkLookup.viaWorkManager(this@FylzApplication)) }
    }
}
