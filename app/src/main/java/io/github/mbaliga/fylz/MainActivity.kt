package io.github.mbaliga.fylz

import android.content.Intent
import android.net.Uri
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
import io.github.mbaliga.fylz.ui.FylzAppShell

/**
 * P0.12 (defects 12/13): `singleTask`, so a second `ACTION_VIEW` launch (another app's "Open
 * with Fylz") while Fylz is already running arrives via [onNewIntent], not a fresh [onCreate].
 * Both read `intent.data` into the same Compose state so either path opens the single-document
 * preview the same way.
 */
class MainActivity : ComponentActivity() {
    private var viewUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fylz", style = CRASH_STYLE)) return
        BackupScheduler(applicationContext).reconcile()
        enableEdgeToEdge()
        viewUri = viewUriFrom(intent)
        // No MaterialTheme wrapper here. FylzAppShell's content owns the theme (FylzTheme,
        // inside FylzV1App), and a bare MaterialTheme above it was one of the places the app's
        // own colours got lost on the way down.
        setContent { FylzAppShell(viewUri = viewUri) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewUri = viewUriFrom(intent)
    }
}

internal fun viewUriFrom(intent: Intent?): Uri? =
    intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data

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
