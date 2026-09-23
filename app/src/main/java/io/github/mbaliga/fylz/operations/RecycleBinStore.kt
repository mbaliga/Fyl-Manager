package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable metadata for Fylz-managed recycle records.
 *
 * File contents remain in the provider's recycle location. This store contains only the minimum
 * metadata required to restore an item and is deliberately excluded from automatic backup.
 */
class RecycleBinStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _records = MutableStateFlow(readRecords())

    /**
     * Live view of this instance's own manifest, refreshed after every [put]/[remove] made
     * through it (P0.8, contract §2.5-2.8) -- lets the Recycle Bin dialog observe changes
     * reactively instead of needing a manual, one-shot re-read. Like [list], it does not see a
     * write made through a different `RecycleBinStore` instance backed by the same preferences.
     */
    val records: StateFlow<List<RecycleRecord>> = _records.asStateFlow()

    @Synchronized
    fun list(): List<RecycleRecord> = readRecords()

    @Synchronized
    fun put(record: RecycleRecord) {
        val records = list().filterNot { it.itemId == record.itemId } + record
        persist(records)
    }

    @Synchronized
    fun remove(itemId: String) {
        persist(list().filterNot { it.itemId == itemId })
    }

    @Synchronized
    fun find(itemId: String): RecycleRecord? = list().firstOrNull { it.itemId == itemId }

    private fun persist(records: List<RecycleRecord>) {
        val encoded = encode(records)
        val currentRaw = preferences.getString(RECORDS_KEY, null)
        val editor = preferences.edit().putString(RECORDS_KEY, encoded)

        // Retain the last parseable manifest. A partially written or externally corrupted current
        // value must never replace the only known-good restore index.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(BACKUP_RECORDS_KEY, currentRaw)
        }

        check(editor.commit()) { "Unable to persist the recycle manifest." }
        _records.value = records
    }

    private fun readRecords(): List<RecycleRecord> {
        val current = decode(preferences.getString(RECORDS_KEY, null))
        if (current != null) return current
        return decode(preferences.getString(BACKUP_RECORDS_KEY, null)).orEmpty()
    }

    private fun encode(records: List<RecycleRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("itemId", record.itemId)
                    .put("originalUri", record.originalUri.toString())
                    .put("recycledUri", record.recycledUri.toString())
                    .put("originalParentUri", record.originalParentUri?.toString())
                    .put("originalDisplayName", record.originalDisplayName)
                    .put("providerAuthority", record.providerAuthority)
                    .put("sizeBytes", record.sizeBytes)
                    .put("recycledAtMillis", record.recycledAtMillis)
                    .put("containerUri", record.containerUri?.toString()),
            )
        }
        return array.toString()
    }

    /** Returns null only when a non-empty payload is malformed. */
    private fun decode(raw: String?): List<RecycleRecord>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        RecycleRecord(
                            itemId = item.getString("itemId"),
                            originalUri = Uri.parse(item.getString("originalUri")),
                            recycledUri = Uri.parse(item.getString("recycledUri")),
                            originalParentUri = item.optString("originalParentUri")
                                .takeIf(String::isNotBlank)
                                ?.let(Uri::parse),
                            originalDisplayName = item.getString("originalDisplayName"),
                            providerAuthority = item.optString("providerAuthority")
                                .takeIf(String::isNotBlank),
                            sizeBytes = item.optLong("sizeBytes", Long.MIN_VALUE)
                                .takeUnless { it == Long.MIN_VALUE },
                            recycledAtMillis = item.getLong("recycledAtMillis"),
                            containerUri = item.optString("containerUri")
                                .takeIf(String::isNotBlank)
                                ?.let(Uri::parse),
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_recycle_manifest"
        const val RECORDS_KEY = "records"
        const val BACKUP_RECORDS_KEY = "records_backup"
    }
}
