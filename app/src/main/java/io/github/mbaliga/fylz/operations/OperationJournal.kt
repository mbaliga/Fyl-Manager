package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.core.model.ItemRef
import io.github.mbaliga.fylz.core.operations.ConflictPolicy
import io.github.mbaliga.fylz.core.operations.FileOperation
import io.github.mbaliga.fylz.core.operations.FileOperationType
import io.github.mbaliga.fylz.core.operations.JournalSchema
import io.github.mbaliga.fylz.core.operations.OperationItem
import io.github.mbaliga.fylz.core.operations.OperationRecoveryPolicy
import io.github.mbaliga.fylz.core.operations.OperationState
import io.github.mbaliga.fylz.storage.toItemRef
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Small durable journal for user-visible file operations.
 *
 * The journal intentionally stores only provider URIs, display names, byte counts, states, and
 * coarse error codes. It never stores file contents, credentials, archive passwords, or AI keys.
 */
class OperationJournal(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        recoverFromPriorProcessIfNeeded()
    }

    @Synchronized
    fun list(): List<FileOperation> = decode(preferences.getString(RECORDS_KEY, null))
        .sortedByDescending(FileOperation::updatedAtMillis)

    @Synchronized
    fun find(id: String): FileOperation? = list().firstOrNull { it.id == id }

    @Synchronized
    fun put(operation: FileOperation) {
        val next = (list().filterNot { it.id == operation.id } + operation)
            .sortedByDescending(FileOperation::updatedAtMillis)
            .take(MAX_RECORDS)
        persist(next)
    }

    @Synchronized
    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
    }

    /** Keeps interrupted records visible until the user explicitly resolves or dismisses them. */
    @Synchronized
    fun clearFinished() {
        persist(
            list().filterNot {
                it.state == OperationState.SUCCEEDED ||
                    it.state == OperationState.FAILED ||
                    it.state == OperationState.CANCELLED
            },
        )
    }

    /**
     * Marks in-flight records as interrupted once per real app-process session.
     *
     * Multiple services may construct their own [OperationJournal] in the same process. A static
     * process session identifier prevents the second instance from misclassifying live work as an
     * interrupted operation merely because it read the same preferences file.
     */
    private fun recoverFromPriorProcessIfNeeded() {
        synchronized(PROCESS_SESSION_LOCK) {
            val previousSession = preferences.getString(PROCESS_SESSION_KEY, null)
            if (previousSession == PROCESS_SESSION_ID) return

            val decoded = decode(preferences.getString(RECORDS_KEY, null))
            val recoveredAt = System.currentTimeMillis()
            val recovered = decoded.map {
                OperationRecoveryPolicy.recoverAfterProcessDeath(it, recoveredAt)
            }
            if (recovered != decoded) persist(recovered)

            check(
                preferences.edit()
                    .putString(PROCESS_SESSION_KEY, PROCESS_SESSION_ID)
                    .commit(),
            ) { "Unable to initialise the operation journal session." }
        }
    }

    private fun persist(records: List<FileOperation>) {
        val root = JSONArray()
        records.forEach { operation ->
            val items = JSONArray()
            operation.items.forEach { item ->
                items.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("source", item.source.toJson())
                        .put("destination", item.destination?.toJson())
                        .put("displayName", item.displayName)
                        .put("expectedBytes", item.expectedBytes)
                        .put("completedBytes", item.completedBytes)
                        .put("state", item.state.name)
                        .put("errorCode", item.errorCode),
                )
            }
            root.put(
                JSONObject()
                    .put("schemaVersion", JournalSchema.CURRENT_VERSION)
                    .put("id", operation.id)
                    .put("type", operation.type.name)
                    .put("conflictPolicy", operation.conflictPolicy.name)
                    .put("state", operation.state.name)
                    .put("createdAtMillis", operation.createdAtMillis)
                    .put("updatedAtMillis", operation.updatedAtMillis)
                    .put("items", items),
            )
        }
        check(preferences.edit().putString(RECORDS_KEY, root.toString()).commit()) {
            "Unable to persist the operation journal."
        }
    }

    private fun ItemRef.toJson(): JSONObject = JSONObject()
        .put("providerId", providerId)
        .put("locationId", locationId)
        .put("opaqueItemId", opaqueItemId)

    /**
     * Reads a `source`/`destination` field by JSON shape rather than by the record's
     * `schemaVersion`, per the migration contract in [JournalSchema]'s KDoc: an object is the
     * current [ItemRef] encoding, a string is a pre-WP-1.1 `Uri`, decoded through
     * `app/storage/ItemRefs.kt`'s adapter into an equivalent ref.
     */
    private fun JSONObject.decodeItemRef(key: String): ItemRef? =
        when (val value = opt(key)) {
            null, JSONObject.NULL -> null
            is JSONObject -> ItemRef(
                providerId = value.getString("providerId"),
                locationId = value.getString("locationId"),
                opaqueItemId = value.getString("opaqueItemId"),
            )
            is String -> value.takeIf(String::isNotBlank)?.let { Uri.parse(it).toItemRef() }
            else -> null
        }

    private fun decode(raw: String?): List<FileOperation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONArray(raw)
            buildList {
                for (index in 0 until root.length()) {
                    val value = root.getJSONObject(index)
                    val itemsJson = value.getJSONArray("items")
                    val items = buildList {
                        for (itemIndex in 0 until itemsJson.length()) {
                            val item = itemsJson.getJSONObject(itemIndex)
                            add(
                                OperationItem(
                                    id = item.getString("id"),
                                    source = requireNotNull(item.decodeItemRef("source")) {
                                        "Operation journal item is missing its source reference."
                                    },
                                    destination = item.decodeItemRef("destination"),
                                    displayName = item.getString("displayName"),
                                    expectedBytes = item.optLong("expectedBytes", Long.MIN_VALUE)
                                        .takeUnless { it == Long.MIN_VALUE },
                                    completedBytes = item.optLong("completedBytes", 0L),
                                    state = OperationState.valueOf(item.getString("state")),
                                    errorCode = item.optString("errorCode").takeIf(String::isNotBlank),
                                ),
                            )
                        }
                    }
                    add(
                        FileOperation(
                            id = value.getString("id"),
                            type = FileOperationType.valueOf(value.getString("type")),
                            items = items,
                            conflictPolicy = ConflictPolicy.valueOf(value.getString("conflictPolicy")),
                            state = OperationState.valueOf(value.getString("state")),
                            createdAtMillis = value.getLong("createdAtMillis"),
                            updatedAtMillis = value.getLong("updatedAtMillis"),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_operation_journal"
        const val RECORDS_KEY = "operations"
        const val PROCESS_SESSION_KEY = "process_session"
        const val MAX_RECORDS = 200

        val PROCESS_SESSION_ID: String = UUID.randomUUID().toString()
        val PROCESS_SESSION_LOCK = Any()
    }
}
