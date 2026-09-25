package io.github.mbaliga.fylz

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery
import io.github.mbaliga.fylz.archive.ArchiveInspector
import io.github.mbaliga.fylz.archive.ArchiveSource
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.DecoderClient
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationRunner
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
     * Archive inspection through the isolated decoder process (M3.2), application-scoped like
     * [operationRunner] and for the same reason: `DecoderClient` owns a `ServiceConnection` that
     * nothing composition-scoped could release, and the binding (and so `:decoders`) should live
     * for a browsing session rather than per call. Bound with the application context. No idle
     * unbind in M3.2 -- once used, the isolated process stays until the app process ends; M3.3
     * measures that cost and decides. Reached as `(context.applicationContext as
     * FylzApplication).archiveInspector`.
     */
    val archiveInspector: ArchiveInspector by lazy {
        ArchiveInspector(ArchiveSource(this, archiveLimits), DecoderClient(this), archiveLimits)
    }

    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this, appLabel = "Fylz")

        // Constructing this here, first, runs OperationJournal's own construction-time recovery
        // (RUNNING/PREFLIGHT/PAUSED -> NEEDS_ATTENTION/"PROCESS_INTERRUPTED") before anything else
        // in the process gets a chance to construct a journal of its own -- OperationRunner.recover
        // (P0.6) depends on that having already happened.
        val journal = OperationJournal(this)
        operationScope.launch { OperationRunner.recover(journal, contentResolver) }
    }
}
