package io.github.mbaliga.fylz.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.MainActivity
import io.github.mbaliga.fylz.formats.Bytes
import io.github.mbaliga.fylz.formats.Confidence
import io.github.mbaliga.fylz.formats.Fallback
import io.github.mbaliga.fylz.formats.FormatCatalog
import io.github.mbaliga.fylz.formats.FormatCategory
import io.github.mbaliga.fylz.formats.FormatSniffer
import io.github.mbaliga.fylz.formats.InspectionResult
import io.github.mbaliga.fylz.formats.SniffResult
import io.github.mbaliga.fylz.formats.inspect.InspectorRegistry
import io.github.mbaliga.fylz.openwith.ContentByteSource
import io.github.mbaliga.fylz.openwith.IncomingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What Fylz shows for a file another app handed it, whatever the file is.
 *
 * The aim is that no file is ever a dead end. In order: say what it is (from its bytes, then its
 * name, then the sender's MIME type) and warn when the name disagrees with the contents; show
 * everything an inspector could read from its structure; preview what can be previewed (images,
 * text); and for the rest, the raw view: a hex dump, readable strings and an entropy reading.
 * Open with…, Save a copy and Share are always there, so a format Fylz cannot show can still go to
 * an app that can.
 */
@Composable
fun ExternalFileScreen(files: List<IncomingFile>, onClose: () -> Unit) {
    if (files.isEmpty()) return
    BackHandler(onBack = onClose)
    var index by remember(files) { mutableIntStateOf(0) }
    val file = files[index.coerceIn(0, files.lastIndex)]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var analysis by remember(file.uri) { mutableStateOf<Analysis?>(null) }
    var failed by remember(file.uri) { mutableStateOf(false) }
    LaunchedEffect(file.uri) {
        val result = withContext(Dispatchers.IO) { analyse(context, file) }
        analysis = result
        failed = result == null
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        if (target != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            context.contentResolver.openOutputStream(target, "wt")?.use { output -> input.copyTo(output) }
                        } != null
                    }.getOrDefault(false)
                }
                Toast.makeText(context, if (ok) "Saved a copy" else "Could not save a copy", Toast.LENGTH_LONG).show()
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text(file.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            (file.sizeBytes ?: analysis?.size)?.let(Bytes::humanSize),
                            if (files.size > 1) "${index + 1} of ${files.size}" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (files.size > 1) {
                    TextButton(onClick = { index = (index - 1).coerceAtLeast(0) }, enabled = index > 0) { Text("Prev") }
                    TextButton(onClick = { index = (index + 1).coerceAtMost(files.lastIndex) }, enabled = index < files.lastIndex) { Text("Next") }
                }
            }
            HorizontalDivider()

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val a = analysis
                when {
                    failed -> Text(
                        "Fylz could not read this file. The app that sent it may have withdrawn access.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    a == null -> Text("Reading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> AnalysisBody(file, a)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openWith(context, file) }) { Text("Open with…") }
                    OutlinedButton(onClick = { saveLauncher.launch(file.displayName) }) { Text("Save a copy") }
                    OutlinedButton(onClick = { share(context, file) }) { Text("Share") }
                }
            }
        }
    }
}

@Composable
private fun AnalysisBody(file: IncomingFile, a: Analysis) {
    val spec = a.sniff.spec
    // 1. What it is.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            spec?.label ?: if (a.sniff.looksLikeText) "Unknown text format" else "Unrecognised format",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val category = spec?.category?.let { categoryLabel(it) }
        Text(
            listOfNotNull(category, confidenceLine(a.sniff)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (spec != null && spec.description.isNotBlank()) Text(spec.description, style = MaterialTheme.typography.bodySmall)
        if (a.sniff.extensionMismatch && spec != null) {
            val ext = file.displayName.substringAfterLast('.', "")
            Text(
                if (ext.isBlank()) "The contents look like ${spec.label}." else "The name says .$ext but the contents look like ${spec.label}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }

    // 2. A picture, when it is one Fylz can draw.
    if (spec != null && spec.id in DRAWABLE_IMAGE_IDS) {
        AsyncImage(
            model = file.uri,
            contentDescription = file.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        )
    }

    // 3. What the inspector read from the structure.
    a.inspection?.let { result ->
        result.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        result.facts.groupBy { it.group ?: "Details" }.forEach { (group, facts) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(group.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                facts.forEach { fact ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text(fact.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                        SelectionContainer(Modifier.weight(0.6f)) { Text(fact.value, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
        result.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    }

    // 4. Text, when it is text.
    val text = a.textPreview ?: a.inspection?.text
    if (!text.isNullOrBlank()) {
        Section("Text") {
            SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            if (a.textTruncated) Text("Showing the start of the file.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // 5. The floor: raw bytes, always available.
    var showRaw by remember(file.uri) { mutableStateOf(spec == null && !a.sniff.looksLikeText) }
    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide raw view" else "Show raw view") }
    if (showRaw) {
        Text(
            "Entropy ${"%.2f".format(a.entropy)} bits per byte: ${Fallback.describeEntropy(a.entropy)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Section("Hex (first ${Bytes.humanSize(a.hexBytes.toLong())})") {
            SelectionContainer { Text(a.hexDump, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
        if (a.strings.isNotEmpty()) {
            Section("Readable strings") {
                SelectionContainer {
                    Text(a.strings.joinToString("\n") { it.text.take(120) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                .padding(12.dp),
        ) { Column { content() } }
    }
}

/** Everything learned about one file, computed off the main thread. */
private class Analysis(
    val size: Long,
    val sniff: SniffResult,
    val inspection: InspectionResult?,
    val hexDump: String,
    val hexBytes: Int,
    val strings: List<Fallback.FoundString>,
    val entropy: Double,
    val textPreview: String?,
    val textTruncated: Boolean,
)

private fun analyse(context: android.content.Context, file: IncomingFile): Analysis? {
    val source = ContentByteSource.open(context, file.uri) ?: return null
    return source.use { src ->
        val header = src.read(0, FormatSniffer.MAX_HEADER_BYTES)
        val sniff = FormatCatalog.sniffer.sniff(header, file.displayName, file.mimeType)
        val inspection = sniff.spec?.let { spec ->
            runCatching { InspectorRegistry.inspect(spec.id, src, file.displayName) }.getOrNull()
        }
        val sample = header.copyOf(minOf(header.size, HEX_BYTES))
        val isText = sniff.looksLikeText || sniff.spec?.text == true
        val textBytes = if (isText) src.read(0, TEXT_BYTES) else ByteArray(0)
        Analysis(
            size = src.size,
            sniff = sniff,
            inspection = inspection,
            hexDump = Fallback.hexDump(sample, maxBytes = HEX_BYTES),
            hexBytes = sample.size,
            strings = Fallback.strings(header.copyOf(minOf(header.size, STRINGS_BYTES)), limit = 60),
            entropy = Fallback.entropy(header.copyOf(minOf(header.size, STRINGS_BYTES))),
            textPreview = if (isText) decodeText(textBytes) else null,
            textTruncated = isText && src.size > TEXT_BYTES,
        )
    }
}

/** Decodes bytes as text, honouring a UTF-16/UTF-8 byte-order mark, with UTF-8 the default. */
private fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 2) {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    val skip = if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF) 3 else 0
    return String(bytes, skip, bytes.size - skip, Charsets.UTF_8)
}

private fun confidenceLine(sniff: SniffResult): String? = when (sniff.confidence) {
    Confidence.MAGIC -> "identified from its contents"
    Confidence.MAGIC_CONTAINER -> "identified from its contents and name"
    Confidence.EXTENSION -> "identified from its name only"
    Confidence.MIME -> "identified from the type the sender gave"
    Confidence.TEXT_HEURISTIC -> "looks like text"
    Confidence.NONE -> null
}

private fun categoryLabel(category: FormatCategory): String =
    category.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun openWith(context: android.content.Context, file: IncomingFile) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file.uri, file.mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Exclude ourselves, or "Open with" would offer Fylz and loop straight back here.
    val chooser = Intent.createChooser(view, "Open with")
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf<Parcelable>(ComponentName(context, MainActivity::class.java)))
    runCatching { context.startActivity(chooser) }
        .onFailure { Toast.makeText(context, "No app can open this file", Toast.LENGTH_LONG).show() }
}

private fun share(context: android.content.Context, file: IncomingFile) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(file.mimeType ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, file.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, "Share")) }
}

/** Formats Coil can draw directly. Everything else falls through to facts and the raw view. */
private val DRAWABLE_IMAGE_IDS = setOf("png", "jpg", "gif", "webp", "bmp", "svg", "avif", "heic", "ico")

private const val HEX_BYTES = 2048
private const val STRINGS_BYTES = 65_536
private const val TEXT_BYTES = 64 * 1024
