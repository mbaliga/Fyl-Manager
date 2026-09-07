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
import io.github.mbaliga.fylz.intents.FylzCommand
import io.github.mbaliga.fylz.intents.FylzIntents
import io.github.mbaliga.fylz.operations.RecycleBinRetentionScheduler
import io.github.mbaliga.fylz.ui.FylzAppShell

/**
 * `android:launchMode="singleTask"` (the manifest's own, Workstream D's), so a second launch --
 * an app widget tap, a static shortcut, Send-to-Fylz's own `startActivity` -- delivers into
 * [onNewIntent] on this same instance rather than spawning a second Activity. [pendingCommand]
 * is the one piece of state both entry points (a cold [onCreate] and a warm [onNewIntent]) write
 * into, decoded by [FylzIntents.parse] and handed down through [FylzAppShell] into
 * [io.github.mbaliga.fylz.ui.FylzV1App]'s workspace, which consumes it exactly once and nulls it
 * back out here via `onCommandConsumed`.
 */
class MainActivity : ComponentActivity() {
    private var pendingCommand by mutableStateOf<FylzCommand?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fylz", style = CRASH_STYLE)) return
        BackupScheduler(applicationContext).reconcile()
        RecycleBinRetentionScheduler(applicationContext).reconcile()
        enableEdgeToEdge()
        // Only parse on a genuinely fresh delivery. A config-change recreation (rotation,
        // dark-mode toggle, multi-window resize, locale change) always supplies a non-null
        // savedInstanceState and re-hands us the *same* Intent via getIntent() -- Android never
        // clears it -- so re-parsing here would silently re-fire the launcher command (Scan,
        // FocusSearch, OpenShelf, OpenTrash, OpenFolder) on every rotation. A real new command
        // only ever arrives via onNewIntent, which sets pendingCommand itself.
        if (savedInstanceState == null) {
            pendingCommand = FylzIntents.parse(intent)
        }
        // No MaterialTheme wrapper here. FylzAppShell's content owns the theme (FylzTheme,
        // inside FylzV1App), and a bare MaterialTheme above it was one of the places the app's
        // own colours got lost on the way down.
        setContent {
            FylzAppShell(
                pendingCommand = pendingCommand,
                onCommandConsumed = { pendingCommand = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCommand = FylzIntents.parse(intent)
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
