package io.github.mbaliga.fylz.storage

import android.content.Context
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.util.FileType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The six buckets the Storage card's segmented bar and legend break usage into.
 *
 * Deliberately coarser than [EntryKind]: a legend needs fewer, bigger-picture groups than the
 * browser's own kind vocabulary -- [DOCUMENTS] folds MARKDOWN/TEXT/PDF into one run, the same
 * grouping `FylzSearch.DOCUMENT_KINDS` already uses for "my documents" -- and it needs two
 * buckets the browser has no name for at all. [ARCHIVES] and [OTHER] are exactly what the owner's
 * mockup omitted from its legend, and on a real device they are usually the largest residue.
 */
enum class StorageKind {
    PHOTOS,
    DOCUMENTS,
    VIDEOS,
    SOUNDS,
    ARCHIVES,
    OTHER,
}

/** [kind]'s legend label. */
fun StorageKind.label(): String = when (this) {
    StorageKind.PHOTOS -> "Photos"
    StorageKind.DOCUMENTS -> "Documents"
    StorageKind.VIDEOS -> "Videos"
    StorageKind.SOUNDS -> "Sounds"
    StorageKind.ARCHIVES -> "Archives"
    StorageKind.OTHER -> "Other"
}

/** Fixed rendering order for the segmented bar and the legend beneath it -- never alphabetical,
 *  so the two never disagree about which run is which. */
val STORAGE_KIND_ORDER: List<StorageKind> = listOf(
    StorageKind.PHOTOS,
    StorageKind.DOCUMENTS,
    StorageKind.VIDEOS,
    StorageKind.SOUNDS,
    StorageKind.ARCHIVES,
    StorageKind.OTHER,
)

/**
 * Where an [EntryKind] the scan walked lands in the Storage card's coarser vocabulary.
 * [EntryKind.DIRECTORY] never actually reaches this -- [StorageScanWorker] only classifies files,
 * since a directory's bytes are already counted as its children's -- but it needs a total answer
 * to stay exhaustive, so it falls into [StorageKind.OTHER] like any other kind nobody named yet.
 */
fun storageKindOf(kind: EntryKind): StorageKind = when (kind) {
    EntryKind.IMAGE -> StorageKind.PHOTOS
    EntryKind.TEXT, EntryKind.MARKDOWN, EntryKind.PDF -> StorageKind.DOCUMENTS
    EntryKind.VIDEO -> StorageKind.VIDEOS
    EntryKind.AUDIO -> StorageKind.SOUNDS
    EntryKind.ARCHIVE -> StorageKind.ARCHIVES
    EntryKind.OTHER, EntryKind.DIRECTORY -> StorageKind.OTHER
}

/** One file [StorageScanWorker] visited, reduced to exactly what classification needs -- no
 *  `java.io.File`, so the tally in [classifyBytes] is pinnable on the JVM with no filesystem. */
data class ScannedFileFacts(val name: String, val mimeType: String, val sizeBytes: Long)

/** Sums [files] into a per-[StorageKind] byte total using the same [io.github.mbaliga.fylz.util.FileType]
 *  classifier the browser itself uses, so a scan and a folder listing never disagree about what
 *  something is. Pure: the actual filesystem walk lives in [StorageScanWorker]. */
fun classifyBytes(files: List<ScannedFileFacts>): Map<StorageKind, Long> {
    val totals = LinkedHashMap<StorageKind, Long>()
    files.forEach { file ->
        val kind = storageKindOf(FileType.classify(file.name, file.mimeType))
        totals[kind] = (totals[kind] ?: 0L) + file.sizeBytes.coerceAtLeast(0L)
    }
    return totals
}

/** One file [StorageScanWorker] flagged as among the largest it saw during the walk -- [uriString]
 *  is a plain string, not a [android.net.Uri], so this data class (and [StorageUsageSnapshot]
 *  carrying a list of them) needs no Robolectric to construct or compare in a pure JVM test. */
data class LargeFileFact(val uriString: String, val displayName: String, val sizeBytes: Long)

/**
 * A completed [StorageScanWorker] pass.
 *
 * [kindBytes] omits a kind entirely rather than storing a zero for it, the same "unknown versus
 * absent" discipline [io.github.mbaliga.fylz.browse.EntryDetails] documents for its own facts --
 * [bytesFor] is where that gets collapsed back to zero for display, once the caller has already
 * decided (by checking for a null snapshot) whether a scan has ever run at all.
 *
 * [largestFiles] is the widest-reaching files the scan's own bounded top-tracker retained (see
 * [StorageScanWorker]'s own KDoc), largest first, capped well under what a whole device's file
 * count could be -- never every file the walk visited. Defaults to empty so a schema-v1 snapshot
 * (written before this field existed) decodes cleanly with "no large-files figure" rather than a
 * missing-field crash; a fresh scan always writes it schema-v2, with whatever it found.
 */
data class StorageUsageSnapshot(
    val scannedAtMillis: Long,
    val kindBytes: Map<StorageKind, Long>,
    val truncated: Boolean = false,
    val largestFiles: List<LargeFileFact> = emptyList(),
) {
    fun bytesFor(kind: StorageKind): Long = kindBytes[kind] ?: 0L
}

/**
 * Persisted result of the last [StorageScanWorker] pass. [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled:
 * a single JSON blob, a `:backup` slot holding the last parseable write so a torn write never
 * costs the Storage card the only figure it has, `@Synchronized` methods, `check(editor.commit())`.
 *
 * One snapshot only, not one per location: under full access a byte total is a device-wide fact
 * (see [FileStorageProvider]'s own KDoc), so there is exactly one thing to remember here, not a
 * key per folder the way [io.github.mbaliga.fylz.canvas.CanvasLayoutStore] needs.
 */
class StorageUsageStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun snapshot(): StorageUsageSnapshot? {
        val current = decode(preferences.getString(SNAPSHOT_KEY, null))
        if (current != null) return current
        return decode(preferences.getString(BACKUP_KEY, null))
    }

    @Synchronized
    fun write(snapshot: StorageUsageSnapshot) {
        val encoded = encode(snapshot)
        val currentRaw = preferences.getString(SNAPSHOT_KEY, null)
        val editor = preferences.edit().putString(SNAPSHOT_KEY, encoded)

        // Retain the last parseable snapshot: a partially written or externally corrupted
        // current value must never replace the only known-good figure the card has.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(BACKUP_KEY, currentRaw)
        }

        check(editor.commit()) { "Unable to persist the storage usage snapshot." }
    }

    private fun encode(snapshot: StorageUsageSnapshot): String {
        val kinds = JSONObject()
        snapshot.kindBytes.forEach { (kind, bytes) -> kinds.put(kind.name, bytes) }
        val largestFiles = JSONArray()
        snapshot.largestFiles.forEach { fact ->
            largestFiles.put(
                JSONObject()
                    .put("uriString", fact.uriString)
                    .put("displayName", fact.displayName)
                    .put("sizeBytes", fact.sizeBytes),
            )
        }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("scannedAtMillis", snapshot.scannedAtMillis)
            .put("truncated", snapshot.truncated)
            .put("kindBytes", kinds)
            .put("largestFiles", largestFiles)
            .toString()
    }

    /**
     * Returns null only when a non-blank payload is malformed -- mirrors
     * [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]. A schema-v1 payload (written before
     * [StorageUsageSnapshot.largestFiles] existed) has no `largestFiles` key at all;
     * [JSONObject.optJSONArray] returning null for it decodes as an empty list rather than a
     * decode failure, so an old snapshot on disk keeps decoding after this build's upgrade.
     */
    private fun decode(raw: String?): StorageUsageSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val value = JSONObject(raw)
            val kinds = value.optJSONObject("kindBytes") ?: JSONObject()
            val kindBytes = buildMap {
                kinds.keys().forEach { key ->
                    val kind = runCatching { StorageKind.valueOf(key) }.getOrNull() ?: return@forEach
                    put(kind, kinds.getLong(key))
                }
            }
            val largestFiles = buildList {
                val array = value.optJSONArray("largestFiles") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val record = array.optJSONObject(index) ?: continue
                    val fact = runCatching {
                        LargeFileFact(
                            uriString = record.getString("uriString"),
                            displayName = record.getString("displayName"),
                            sizeBytes = record.getLong("sizeBytes"),
                        )
                    }.getOrNull()
                    if (fact != null) add(fact)
                }
            }
            StorageUsageSnapshot(
                scannedAtMillis = value.getLong("scannedAtMillis"),
                kindBytes = kindBytes,
                truncated = value.optBoolean("truncated", false),
                largestFiles = largestFiles,
            )
        }.getOrNull()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_storage_usage"
        const val SNAPSHOT_KEY = "snapshot"
        const val BACKUP_KEY = "snapshot:backup"
        const val SCHEMA_VERSION = 2
    }
}
