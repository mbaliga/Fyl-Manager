package io.github.mbaliga.fylz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import dev.aarso.crashrecovery.CrashRecovery
import io.github.mbaliga.fylz.backup.BackupScheduler
import io.github.mbaliga.fylz.ui.FylzAppShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fylz")) return
        BackupScheduler(applicationContext).reconcile()
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                FylzAppShell()
            }
        }
    }
}
