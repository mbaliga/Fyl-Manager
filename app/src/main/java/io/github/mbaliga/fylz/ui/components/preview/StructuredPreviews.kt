package io.github.mbaliga.fylz.ui.components.preview

import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.data.StructuredDocumentReaders
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.components.UniversalInspectorPreview
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Preview wave 1's screens: word-processing text, EML, EPUB, RAW's embedded preview, a
 * read-only SQLite peek, and ICS/VCF cards. Parsing lives in
 * [StructuredDocumentReaders] (pure, tested); these compose what it returns, and every
 * failure route lands in the bounded inspector with the reason — the floor that already
 * guarantees nothing in Fylz ever refuses to open.
 */

// ── Shared scaffolding ───────────────────────────────────────────────────────────────

@Composable
private fun StructuredScaffold(
    headline: String,
    caveat: String?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(headline, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (caveat != null) {
            Text(
                caveat,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.padding(top = 12.dp)) { content() }
    }
}

@Composable
private fun Loading(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

// ── Word processing ──────────────────────────────────────────────────────────────────

@Composable
fun DocumentTextPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsed by produceState<Result<StructuredDocumentReaders.ExtractedText>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                StructuredDocumentReaders.readWordProcessing(descriptor.extension) {
                    context.contentResolver.openInputStream(entry.uri) ?: error("Unable to read this document.")
                }
            }
        }
    }
    when (val result = parsed) {
        null -> Loading(modifier)
        else -> result.fold(
            onSuccess = { extracted ->
                StructuredScaffold(
                    headline = "Document text",
                    caveat = "Text only — layout, images and tables are not reproduced." +
                        if (extracted.truncated) " Long document: showing the beginning." else "",
                    modifier = modifier,
                ) {
                    Text(extracted.text, style = MaterialTheme.typography.bodyMedium)
                }
            },
            onFailure = { UniversalInspectorPreview(entry, descriptor, modifier, it.message) },
        )
    }
}

// ── EML ──────────────────────────────────────────────────────────────────────────────

@Composable
fun EmailPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsed by produceState<Result<StructuredDocumentReaders.EmailPreview>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                StructuredDocumentReaders.readEml {
                    context.contentResolver.openInputStream(entry.uri) ?: error("Unable to read this message.")
                }
            }
        }
    }
    when (val result = parsed) {
        null -> Loading(modifier)
        else -> result.fold(
            onSuccess = { mail ->
                StructuredScaffold(
                    headline = mail.subject ?: "(no subject)",
                    caveat = if (mail.bodyTruncated) "Long message: showing the beginning." else null,
                    modifier = modifier,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        mail.from?.let { HeaderLine("From", it) }
                        mail.to?.let { HeaderLine("To", it) }
                        mail.date?.let { HeaderLine("Date", it) }
                        if (mail.attachmentNames.isNotEmpty()) {
                            // Named, never opened: the preview says what the mail carries.
                            HeaderLine("Attachments", mail.attachmentNames.joinToString(", "))
                        }
                        Text(
                            mail.bodyText.ifBlank { "(no readable text body)" },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            },
            onFailure = { UniversalInspectorPreview(entry, descriptor, modifier, it.message) },
        )
    }
}

@Composable
private fun HeaderLine(label: String, value: String) {
    Row {
        Text(
            "$label  ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

// ── EPUB ─────────────────────────────────────────────────────────────────────────────

@Composable
fun EpubTextPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsed by produceState<Result<StructuredDocumentReaders.ExtractedText>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                StructuredDocumentReaders.readEpub({
                    context.contentResolver.openInputStream(entry.uri) ?: error("Unable to read this book.")
                })
            }
        }
    }
    when (val result = parsed) {
        null -> Loading(modifier)
        else -> result.fold(
            onSuccess = { extracted ->
                StructuredScaffold(
                    headline = "Book text",
                    caveat = "The opening chapters, as text — not the book's own layout." +
                        if (extracted.truncated) " Showing the beginning." else "",
                    modifier = modifier,
                ) {
                    Text(extracted.text, style = MaterialTheme.typography.bodyMedium)
                }
            },
            onFailure = { UniversalInspectorPreview(entry, descriptor, modifier, it.message) },
        )
    }
}

// ── RAW: the embedded preview ────────────────────────────────────────────────────────

@Composable
fun RawEmbeddedPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thumb by produceState<Result<ByteArray?>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(entry.uri)?.use { stream ->
                    ExifInterface(stream).thumbnailBytes
                }
            }
        }
    }
    when (val result = thumb) {
        null -> Loading(modifier)
        else -> {
            val bytes = result.getOrNull()
            if (bytes == null) {
                UniversalInspectorPreview(
                    entry, descriptor, modifier,
                    "This RAW file carries no embedded preview Fylz can show; full RAW development is not built in.",
                )
            } else {
                Column(modifier.fillMaxSize()) {
                    Text(
                        "Embedded camera preview — not a RAW development.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    AsyncImage(
                        model = bytes,
                        contentDescription = entry.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// ── SQLite peek ──────────────────────────────────────────────────────────────────────

private data class SqlitePeek(
    val tables: List<Pair<String, Long>>,
    val shownTable: String?,
    val columns: List<String>,
    val rows: List<List<String>>,
)

@Composable
fun DatabasePeekPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parsed by produceState<Result<SqlitePeek>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { peekSqlite(context, entry) }
        }
    }
    when (val result = parsed) {
        null -> Loading(modifier)
        else -> result.fold(
            onSuccess = { peek ->
                StructuredScaffold(
                    headline = "Database — read-only peek",
                    caveat = "A copy is opened; the file itself is never written.",
                    modifier = modifier,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        peek.tables.forEach { (name, count) ->
                            Text(
                                "$name — $count ${if (count == 1L) "row" else "rows"}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (name == peek.shownTable) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        if (peek.shownTable != null && peek.rows.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            ) {
                                Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                                    Row {
                                        peek.columns.forEach { column ->
                                            Cell(column, header = true)
                                        }
                                    }
                                    peek.rows.forEach { row ->
                                        Row { row.forEach { Cell(it, header = false) } }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            onFailure = { UniversalInspectorPreview(entry, descriptor, modifier, it.message) },
        )
    }
}

@Composable
private fun Cell(value: String, header: Boolean) {
    Text(
        value.take(48),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 12.dp, bottom = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

private fun peekSqlite(context: android.content.Context, entry: FileEntry): SqlitePeek {
    // SQLite needs a real seekable file; a bounded copy goes to cache and is deleted after.
    // The copy also guarantees read-only-ness structurally: the original is never even handed
    // to the database layer.
    val cache = File.createTempFile("fylz-dbpeek-", ".db", context.cacheDir)
    try {
        context.contentResolver.openInputStream(entry.uri)?.use { input ->
            cache.outputStream().use { out ->
                var copied = 0L
                val buffer = ByteArray(64 * 1024)
                while (copied < MAX_DB_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                }
                if (copied >= MAX_DB_BYTES) error("This database is larger than the ${MAX_DB_BYTES / (1024 * 1024)} MB peek limit.")
            }
        } ?: error("Unable to read this database.")

        SQLiteDatabase.openDatabase(cache.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = mutableListOf<Pair<String, Long>>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name LIMIT 40",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val count = db.rawQuery("SELECT COUNT(*) FROM \"$name\"", null).use { c ->
                        if (c.moveToFirst()) c.getLong(0) else 0L
                    }
                    tables += name to count
                }
            }
            val shown = tables.maxByOrNull { it.second }?.first
            var columns = emptyList<String>()
            val rows = mutableListOf<List<String>>()
            if (shown != null) {
                db.rawQuery("SELECT * FROM \"$shown\" LIMIT 12", null).use { cursor ->
                    columns = cursor.columnNames.toList()
                    while (cursor.moveToNext()) {
                        rows += (0 until cursor.columnCount).map { index ->
                            runCatching { cursor.getString(index) ?: "NULL" }.getOrDefault("(blob)")
                        }
                    }
                }
            }
            return SqlitePeek(tables, shown, columns, rows)
        }
    } finally {
        cache.delete()
    }
}

private const val MAX_DB_BYTES = 64L * 1024 * 1024

// ── ICS / VCF cards ──────────────────────────────────────────────────────────────────

@Composable
fun StructuredCardPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val calendar = remember(descriptor.rendererId) { descriptor.rendererId == "calendar-card" }
    val parsed by produceState<Result<List<StructuredDocumentReaders.StructuredCard>>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val open = {
                    context.contentResolver.openInputStream(entry.uri) ?: error("Unable to read this file.")
                }
                if (calendar) StructuredDocumentReaders.readCalendar(open) else StructuredDocumentReaders.readContacts(open)
            }
        }
    }
    when (val result = parsed) {
        null -> Loading(modifier)
        else -> result.fold(
            onSuccess = { cards ->
                if (cards.isEmpty()) {
                    UniversalInspectorPreview(entry, descriptor, modifier, "No entries found in this file.")
                    return@fold
                }
                StructuredScaffold(
                    headline = if (calendar) "Calendar" else "Contacts",
                    caveat = cards.firstOrNull()?.more?.takeIf { it > 0 }?.let { "…and $it more not shown." },
                    modifier = modifier,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        cards.forEach { card ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(card.title, style = MaterialTheme.typography.titleSmall)
                                    card.fields.forEach { field -> HeaderLine(field.label, field.value) }
                                }
                            }
                        }
                    }
                }
            },
            onFailure = { UniversalInspectorPreview(entry, descriptor, modifier, it.message) },
        )
    }
}
