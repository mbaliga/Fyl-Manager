package io.github.mbaliga.fylz

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery

/**
 * Installs the shared Hyle-constellation crash-recovery handler (dev.aarso:crash-recovery,
 * wired via the hyle-design-system submodule + includeBuild). A crash is captured to an
 * app-private file only; nothing is ever transmitted anywhere. [MainActivity] checks for a
 * pending report first thing in `onCreate` and shows the recovery screen instead of its
 * normal content when one exists.
 */
class FylzApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this, appLabel = "Fylz")
    }
}
