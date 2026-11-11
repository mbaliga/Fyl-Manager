package io.github.mbaliga.fylz

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.crashrecovery.CrashRecoveryStyle
import io.github.mbaliga.fylz.backup.BackupScheduler
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.SafStorageProvider
import io.github.mbaliga.fylz.ui.FylzAppShell
import io.github.mbaliga.fylz.widget.FylzWidgets
import io.github.mbaliga.fylz.widget.WidgetDestination
import io.github.mbaliga.fylz.widget.WidgetIntents
import io.github.mbaliga.fylz.widget.WidgetLaunch

class MainActivity : ComponentActivity() {

    /** Where the latest home-screen widget tap asked to land; cleared once the UI has acted on it. */
    private var widgetDestination by mutableStateOf<WidgetDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fylz", style = CRASH_STYLE)) return
        BackupScheduler(applicationContext).reconcile()
        // A recreated activity (rotation, process restore) must not replay an old widget tap.
        if (savedInstanceState == null) widgetDestination = widgetDestinationOf(intent)
        enableEdgeToEdge()
        // No MaterialTheme wrapper here. FylzAppShell's content owns the theme (FylzTheme,
        // inside FylzV1App), and a bare MaterialTheme above it was one of the places the app's
        // own colours got lost on the way down.
        setContent {
            FylzAppShell(
                widgetDestination = widgetDestination,
                onWidgetDestinationHandled = { widgetDestination = null },
            )
        }
    }

    /** singleTask: a widget tap while Fylz is open arrives here rather than in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetDestinationOf(intent)?.let { widgetDestination = it }
    }

    /**
     * Leaving Fylz is when its home-screen widgets most likely went stale (files written, folders
     * browsed, space used), so they are redrawn then. Coalesced, and a no-op with no widget placed.
     */
    override fun onStop() {
        super.onStop()
        FylzWidgets.requestRefresh(this)
    }

    /**
     * MainActivity is exported, so these extras are untrusted: [WidgetLaunch.parse] accepts a
     * folder only from the standard list and a volume only if it is mounted now.
     *
     * Extras are read only for the widget action, and inside runCatching: on API 31-32 the first
     * read of a Bundle unparcels every value, so another app's intent carrying a Parcelable or
     * Serializable class Fylz doesn't have would otherwise throw here and crash the launch.
     */
    private fun widgetDestinationOf(intent: Intent?): WidgetDestination? {
        if (intent?.action != WidgetIntents.ACTION_OPEN) return null
        return runCatching {
            val destination = intent.getStringExtra(WidgetLaunch.EXTRA_DESTINATION) ?: return@runCatching null
            WidgetLaunch.parse(
                destination = destination,
                folder = intent.getStringExtra(WidgetLaunch.EXTRA_FOLDER),
                volume = intent.getStringExtra(WidgetLaunch.EXTRA_VOLUME),
                allowedFolders = SafStorageProvider.STANDARD_DIRECTORIES.map { it.second }.toSet(),
                mountedVolumes = if (destination == WidgetLaunch.VOLUME) {
                    FylzFilesDocumentsProvider.discoverVolumes(applicationContext).map { it.rootId }.toSet()
                } else {
                    emptySet()
                },
            )
        }.getOrNull()
    }
}

/**
 * Fylz's accent, handed to the recovery screen.
 *
 * crash-recovery ships its own neutral surface and takes only an accent from its host, so a
 * crash surface arrives in the colours of the app the user was actually in. These are the MOSS
 * preset as plain @ColorInt values — the module holds no dependency on Compose or on any design
 * system and must be handed platform colours.
 */
private val CRASH_STYLE = CrashRecoveryStyle.accent(
    light = 0xFF315F49.toInt(),
    onLight = 0xFFFFFFFF.toInt(),
    dark = 0xFF9BD3B3.toInt(),
    onDark = 0xFF10281A.toInt(),
)
