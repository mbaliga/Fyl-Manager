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
import io.github.mbaliga.fylz.openwith.IncomingFiles
import io.github.mbaliga.fylz.ui.FylzAppShell

class MainActivity : ComponentActivity() {

    /** Files another app asked Fylz to open (View / Edit / Send). Empty when launched normally. */
    private var incoming by mutableStateOf<List<android.net.Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation (rotation, theme change) keeps the same intent: restore what was on screen
        // instead of re-reading it, so a file the user already closed does not come back.
        incoming = if (savedInstanceState != null) {
            savedInstanceState.getStringArrayList(STATE_URIS).orEmpty().map(android.net.Uri::parse)
        } else {
            IncomingFiles.urisFrom(intent)
        }
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fylz", style = CRASH_STYLE)) return
        BackupScheduler(applicationContext).reconcile()
        enableEdgeToEdge()
        // No MaterialTheme wrapper here. FylzAppShell's content owns the theme (FylzTheme,
        // inside FylzV1App), and a bare MaterialTheme above it was one of the places the app's
        // own colours got lost on the way down.
        setContent {
            FylzAppShell(
                incomingFiles = incoming,
                onIncomingHandled = { incoming = emptyList() },
            )
        }
    }

    // singleTask: a second "open with Fylz" while the app is already running arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val files = IncomingFiles.urisFrom(intent)
        if (files.isNotEmpty()) incoming = files
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_URIS, ArrayList(incoming.map { it.toString() }))
    }

    private companion object {
        const val STATE_URIS = "incoming_uris"
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
