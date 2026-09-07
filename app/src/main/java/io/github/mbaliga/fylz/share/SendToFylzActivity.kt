package io.github.mbaliga.fylz.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.staging.ShelfItem
import io.github.mbaliga.fylz.staging.ShelfStore
import io.github.mbaliga.fylz.storage.toItemRef
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The share-in door. `ACTION_SEND` / `ACTION_SEND_MULTIPLE` from any other app targets this
 * activity, which copies every shared stream into the user's configured [InboxPreferences]
 * folder and adds each copy to the [ShelfStore] -- a share-sheet target that never needs to know
 * about tabs, locations, or any of the rest of the app's own navigation to make a shared file
 * land somewhere useful.
 *
 * `android:excludeFromRecents="true"` in the manifest: this activity's whole life is "copy,
 * toast, finish," and a stale copy of it lingering in the recents list would let a user reopen a
 * share flow that has nothing left to do.
 */
class SendToFylzActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streams = extractStreams(intent)
        if (streams.isEmpty()) {
            finish()
            return
        }
        setContent {
            FylzTheme(
                themeMode = ThemeMode.SYSTEM,
                accentPreset = AccentPreset.MOSS,
                dynamicColor = true,
            ) {
                SendToFylzScreen(streams = streams, onDone = ::finish)
            }
        }
    }
}

/**
 * `ClipData`-aware: a share sheet routinely populates `ClipData` even for a single-item
 * `ACTION_SEND`, and `SEND_MULTIPLE` senders vary on whether the list lands in `ClipData` or the
 * legacy `ArrayList` extra -- checking `ClipData` first and falling back to the action-specific
 * extra covers every sender this app has been asked to handle.
 */
@Suppress("DEPRECATION") // getParcelableExtra(String): the typed 2-arg overload is API 33; minSdk here is 31.
private fun extractStreams(intent: Intent): List<Uri> {
    val clip = intent.clipData
    if (clip != null && clip.itemCount > 0) {
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
    return when (intent.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::listOf).orEmpty()
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }
}

@Composable
private fun SendToFylzScreen(streams: List<Uri>, onDone: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val inboxPreferences = remember { InboxPreferences(appContext) }
    var location by remember { mutableStateOf(inboxPreferences.location()) }
    // Read through stringResource (recomposition-aware), not context.getString() -- same reason
    // sourceCrumb below is hoisted out of the LaunchedEffect it feeds: this activity's onCreate
    // theme is ThemeMode.SYSTEM/dynamicColor=true, so a value read once via LocalContext.current
    // inside a callback would go stale across a configuration change instead of following one.
    val folderFailedMessage = stringResource(R.string.share_inbox_folder_failed)
    val defaultInboxName = stringResource(R.string.share_inbox_default_name)

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (documentId == null) {
            Toast.makeText(context, folderFailedMessage, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val name = documentId.substringAfterLast('/').ifBlank { defaultInboxName }
        val chosen = InboxLocation(treeUri, folderUri, name)
        inboxPreferences.setLocation(chosen)
        location = chosen
    }

    val currentLocation = location
    if (currentLocation == null) {
        InboxSetupScreen(fileCount = streams.size, onChooseFolder = { folderPicker.launch(null) })
        return
    }

    val sourceCrumb = stringResource(R.string.share_source_crumb)
    LaunchedEffect(currentLocation, streams) {
        val result = copyAndShelve(appContext, streams, currentLocation, sourceCrumb)
        Toast.makeText(context, describeResult(context, result), Toast.LENGTH_LONG).show()
        onDone()
    }

    CopyingScreen()
}

@Composable
private fun InboxSetupScreen(fileCount: Int, onChooseFolder: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.share_inbox_title, fileCount),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.share_inbox_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            TactileButton(
                text = stringResource(R.string.share_choose_inbox_folder),
                onClick = onChooseFolder,
                style = TactileButtonStyle.PRIMARY,
            )
        }
    }
}

@Composable
private fun CopyingScreen() {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.share_copying))
        }
    }
}

// ── copy + shelve ─────────────────────────────────────────────────────────────────────

private data class CopyResult(val shelved: Int, val failed: Int)

/**
 * Copies every stream into [location], adding a [ShelfItem] for each success. Per-item failure
 * (an unreadable source, a provider that refuses `createDocument`) is caught and skipped -- one
 * bad share must never cost the rest of the batch, per the plan's own "continue with the rest,
 * honest toast" requirement. Shelved items are batched into a single [ShelfStore.add] call once
 * every copy has settled, rather than one write per file.
 */
private suspend fun copyAndShelve(
    context: Context,
    streams: List<Uri>,
    location: InboxLocation,
    sourceCrumb: String,
): CopyResult = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val destinationDoc = DocumentFile.fromTreeUri(context, location.treeUri)
    var shelved = 0
    var failed = 0
    val staged = mutableListOf<ShelfItem>()

    streams.forEach { source ->
        runCatching {
            val (rawName, mimeType) = metadataFor(resolver, source)
            val name = uniqueChildName(destinationDoc, rawName)
            val newUri = DocumentsContract.createDocument(resolver, location.folderUri, mimeType, name)
                ?: error("The Inbox folder could not create $name.")
            val bytes = copyStream(resolver, source, newUri)
            staged += ShelfItem(
                ref = newUri.toItemRef(),
                displayName = name,
                kind = FileType.classify(name, mimeType),
                isDirectory = false,
                sizeBytes = bytes,
                modifiedAtMillis = null,
                addedAtMillis = System.currentTimeMillis(),
                sourceCrumb = sourceCrumb,
            )
        }.onSuccess { shelved += 1 }.onFailure { failed += 1 }
    }

    if (staged.isNotEmpty()) ShelfStore(context).add(staged)
    CopyResult(shelved, failed)
}

private fun metadataFor(resolver: ContentResolver, uri: Uri): Pair<String, String> {
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) cursor.getString(index) else null
    }
    val name = displayName?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "shared-file"
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    return name to mimeType
}

/** [FileOperationService][io.github.mbaliga.fylz.operations.FileOperationService].uniqueName-
 *  modelled: append " (2)", " (3)", ... before the extension until the Inbox has no child by
 *  that name. `destination == null` (the tree URI failed to resolve as a `DocumentFile`, which
 *  should not happen for a tree this app just took a persistable grant on) degrades to trusting
 *  the requested name outright rather than throwing. */
private fun uniqueChildName(destination: DocumentFile?, requestedName: String): String {
    if (destination == null || destination.findFile(requestedName) == null) return requestedName
    val dot = requestedName.lastIndexOf('.')
    val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
    val extension = if (dot > 0) requestedName.substring(dot) else ""
    var index = 2
    while (true) {
        val candidate = "$base ($index)$extension"
        if (destination.findFile(candidate) == null) return candidate
        index += 1
    }
}

/** [DocumentRepository][io.github.mbaliga.fylz.data.DocumentRepository].copyStream-modelled: an
 *  8 KiB buffer, streamed straight through with no whole-file buffering. */
private fun copyStream(resolver: ContentResolver, source: Uri, destination: Uri): Long {
    val input = resolver.openInputStream(source) ?: error("Unable to read the shared file.")
    val output = resolver.openOutputStream(destination, "w") ?: error("Unable to write into the Inbox.")
    var total = 0L
    input.use { src ->
        output.use { dst ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = src.read(buffer)
                if (count < 0) break
                dst.write(buffer, 0, count)
                total += count
            }
            dst.flush()
        }
    }
    return total
}

private fun describeResult(context: Context, result: CopyResult): String = when {
    result.shelved == 0 -> context.getString(R.string.share_result_all_failed)
    result.failed == 0 -> context.getString(R.string.share_result_success, result.shelved)
    else -> context.getString(R.string.share_result_partial, result.shelved, result.failed)
}
