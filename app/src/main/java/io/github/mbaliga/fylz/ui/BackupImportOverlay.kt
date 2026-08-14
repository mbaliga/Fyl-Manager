package io.github.mbaliga.fylz.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.mbaliga.fylz.backup.BackupImporter
import kotlinx.coroutines.launch

/**
 * Rediscovers verified manifest-bearing backup folders. There is no dialog of its own — the whole
 * journey is the system folder picker — so `open` means "launch it now" and the picker's own
 * result callback is what reports back down through [onDismiss], whichever way it was left (a
 * folder chosen or the user backing out).
 */
@Composable
fun BackupImportHost(open: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { BackupImporter(context.applicationContext) }

    fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onDismiss()
        if (uri == null) return@rememberLauncherForActivityResult
        persist(uri)
        scope.launch {
            val result = importer.importDestination(uri)
            val summary = buildString {
                append("Imported ${result.importedSnapshots} backup")
                if (result.importedSnapshots != 1) append('s')
                if (result.existingSnapshots > 0) append("; ${result.existingSnapshots} already known")
                if (result.invalidFolders > 0) append("; ${result.invalidFolders} invalid")
            }
            Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(open) {
        if (open) picker.launch(null)
    }
}
