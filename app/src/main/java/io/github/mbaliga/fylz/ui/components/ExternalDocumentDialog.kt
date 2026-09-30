package io.github.mbaliga.fylz.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.data.SaveResult
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.resolvePreviewKind
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.launch

/**
 * A single document opened from outside Fylz (P0.12: `ACTION_VIEW`, "Open with Fylz"), shown at
 * full size rather than in the docked/floating panes browsing normally uses -- there is no file
 * list this document belongs to, so there's nothing to dock next to. Reuses [PreviewPane]
 * entirely (including its own "Open with…" button) and adds the two actions a standalone document
 * needs that browsing's selection action bar normally provides: Share and Save a copy to….
 */
@Composable
fun ExternalDocumentDialog(
    entry: FileEntry,
    repository: DocumentRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var textContent by remember(entry.uri) { mutableStateOf<String?>(null) }
    var textTruncated by remember(entry.uri) { mutableStateOf(false) }
    var textEncodingOk by remember(entry.uri) { mutableStateOf(true) }
    var textHasBom by remember(entry.uri) { mutableStateOf(false) }
    var editorValue by remember(entry.uri) { mutableStateOf("") }
    var loading by remember(entry.uri) { mutableStateOf(false) }

    LaunchedEffect(entry.uri) {
        textContent = null
        editorValue = ""
        val kind = resolvePreviewKind(entry, context.contentResolver)
        if (!FileType.isTextPreviewable(kind)) return@LaunchedEffect
        loading = true
        runCatching { repository.readText(entry.uri) }
            .onSuccess {
                textContent = it.value
                textTruncated = it.truncated
                textEncodingOk = it.encodingOk
                textHasBom = it.hasBom
                editorValue = it.value
            }
            .onFailure { textContent = it.message ?: "Unable to preview" }
        loading = false
    }

    fun save() {
        scope.launch {
            when (val result = repository.writeText(entry.uri, editorValue, hasBom = textHasBom)) {
                is SaveResult.Success -> {
                    textContent = editorValue
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                }
                is SaveResult.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(entry.mimeType),
    ) { destination ->
        if (destination != null) {
            scope.launch {
                runCatching { repository.copyStream(entry.uri, destination) }
                    .onSuccess { Toast.makeText(context, "Saved a copy", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, it.message ?: "Unable to save a copy", Toast.LENGTH_LONG).show() }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { saveCopyLauncher.launch(entry.name) }) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = "Save a copy to…")
                    }
                    IconButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND)
                            .setType(entry.mimeType)
                            .putExtra(Intent.EXTRA_STREAM, entry.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(shareIntent, entry.name)) }
                            .onFailure { Toast.makeText(context, "No app can share this file.", Toast.LENGTH_SHORT).show() }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share")
                    }
                }
                PreviewPane(
                    entry = entry,
                    textContent = textContent,
                    textTruncated = textTruncated,
                    textEncodingOk = textEncodingOk,
                    loading = loading,
                    editorValue = editorValue,
                    onEditorValueChange = { editorValue = it },
                    onSave = ::save,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
