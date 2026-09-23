package io.github.mbaliga.fylz

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery
import io.github.mbaliga.fylz.operations.OperationRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val operationRunner: OperationRunner by lazy { OperationRunner(operationScope) }

    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this, appLabel = "Fylz")
    }
}
