package io.github.mbaliga.fylz

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery
import io.github.mbaliga.fylz.model.StoreChanges
import io.github.mbaliga.fylz.widget.FylzWidgets

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
        // Store writes redraw the home-screen widgets (docs/widgets.md). Installed here, once per
        // process, so the stores never depend on the widget package.
        StoreChanges.listener = FylzWidgets::onStoreChanged
    }
}
