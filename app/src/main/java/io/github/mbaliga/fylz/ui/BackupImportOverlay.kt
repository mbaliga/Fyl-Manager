package io.github.mbaliga.fylz.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mbaliga.fylz.backup.BackupImporter
import kotlinx.coroutines.launch

@Composable
fun BackupImportOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { BackupImporter(context.applicationContext) }

    fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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

    FloatingActionButton(
        onClick = { picker.launch(null) },
        modifier = modifier,
    ) {
        Icon(Icons.Outlined.RestorePage, contentDescription = "Import existing backups")
    }
}
