package io.github.mbaliga.fylz.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.data.ImageExportFormat
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Re-encodes a single image into a *different* one of [ImageExportFormat.SUPPORTED_MIME_TYPES] --
 * unlike [AnnotateOverlay], there is no "Save" (overwrite) here: the original file's own extension
 * names its own format, so writing a different format's bytes under the same name and extension
 * would be a mismatch this app has no business creating. Save As is the only honest path, with the
 * suggested filename's extension swapped to match the format actually chosen.
 *
 * @param entry the single selected image; the caller (`SelectionActionPolicy.convertImage` plus an
 *   [ImageExportFormat.SUPPORTED_MIME_TYPES] check) has already established there is exactly one
 *   and it is a format this can decode and re-encode.
 */
@Composable
fun ConvertImageFormatOverlay(entry: FileEntry, repository: DocumentRepository, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(entry.uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val sourceFormat = remember(entry.mimeType) { ImageExportFormat.fromMimeType(entry.mimeType) }
    val targetOptions = remember(sourceFormat) { ImageExportFormat.entries.filterNot { it == sourceFormat } }
    var targetFormat by remember(entry.uri) { mutableStateOf(targetOptions.first()) }

    LaunchedEffect(entry.uri) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(entry.uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
        if (decoded == null) loadFailed = true else bitmap = decoded
    }

    fun suggestedName(format: ImageExportFormat): String {
        val base = entry.name.substringBeforeLast('.', missingDelimiterValue = entry.name)
        return "$base.${format.extension}"
    }

    fun save(destinationUri: Uri, format: ImageExportFormat) {
        val source = bitmap ?: return
        scope.launch {
            busy = true
            runCatching { repository.writeBitmap(destinationUri, source, format.compressFormat, format.quality) }
                .onSuccess {
                    Toast.makeText(context, "Saved as ${format.label}", Toast.LENGTH_LONG).show()
                    onSaved()
                }
                .onFailure { failure ->
                    Toast.makeText(context, failure.message ?: "Unable to save the image.", Toast.LENGTH_LONG).show()
                }
            busy = false
        }
    }

    // One launcher per possible output format: CreateDocument's mime type is fixed at
    // registration, not at launch, so a single launcher cannot serve all three -- the same
    // constraint ArchiveToolsOverlay's dual ZIP/7z launchers work around, one call site per
    // format rather than a loop, so each registration gets its own stable, distinct call site.
    val jpegLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ImageExportFormat.JPEG.mimeType),
    ) { destination -> if (destination != null) save(destination, ImageExportFormat.JPEG) }
    val pngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ImageExportFormat.PNG.mimeType),
    ) { destination -> if (destination != null) save(destination, ImageExportFormat.PNG) }
    val webpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ImageExportFormat.WEBP.mimeType),
    ) { destination -> if (destination != null) save(destination, ImageExportFormat.WEBP) }

    fun launcherFor(format: ImageExportFormat) = when (format) {
        ImageExportFormat.JPEG -> jpegLauncher
        ImageExportFormat.PNG -> pngLauncher
        ImageExportFormat.WEBP -> webpLauncher
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (!busy) onDismiss() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Cancel")
                    }
                    Text("Convert format", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        loadFailed -> Text(
                            "Unable to open this image.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                        bitmap == null -> CircularProgressIndicator()
                        else -> Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "Image being converted",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Save as",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    targetOptions.forEach { format ->
                        TactileOptionRow(text = format.label, selected = targetFormat == format, onClick = { targetFormat = format })
                    }
                    TactileButton(
                        text = "Save as…",
                        onClick = { launcherFor(targetFormat).launch(suggestedName(targetFormat)) },
                        enabled = !busy && bitmap != null,
                        fillWidth = true,
                    )
                }
            }
        }
    }
}
