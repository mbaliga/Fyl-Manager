package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.core.model.ItemRef
import io.github.mbaliga.fylz.core.model.VersionStamp
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

    /**
     * Marks one record undone (v4) without touching anything else about it. Deliberately does
     * NOT bump `updatedAtMillis`: the undo's own inverse operation is the record of when the
     * undo happened; rewriting this record's time would shuffle history order for no reader.
     */
    @Synchronized
    fun markUndone(id: String) {
        val current = find(id) ?: return
        put(current.copy(undone = true))
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
                        .put("errorCode", item.errorCode)
                        .put("sourceStamp", item.sourceStamp?.toJson())
                        .put("destinationStamp", item.destinationStamp?.toJson()),
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
                    // v4, the Undo fields. Empty segment lists and false are still written --
                    // absent-vs-empty must not become a meaning.
                    .put("sourceParentRoot", operation.sourceParentRoot?.toJson())
                    .put("sourceParentSegments", JSONArray(operation.sourceParentSegments))
                    .put("destinationRoot", operation.destinationRoot?.toJson())
                    .put("destinationSegments", JSONArray(operation.destinationSegments))
                    .put("undone", operation.undone)
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

    private fun VersionStamp.toJson(): JSONObject = when (this) {
        is VersionStamp.Composite -> JSONObject()
            .put("kind", "composite")
            .put("sizeBytes", sizeBytes)
            .put("modifiedAtMillis", modifiedAtMillis)
        is VersionStamp.Revision -> JSONObject()
            .put("kind", "revision")
            .put("token", token)
    }

    /** Reads a v4 display-name segment list; absent or malformed decodes to empty. */
    private fun JSONObject.decodeSegments(key: String): List<String> {
        val value = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until value.length()) {
                value.optString(index).takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    /**
     * Reads an optional schema-v3 version stamp. Absent field, unrecognized kind, or a
     * malformed object all decode to null — per [JournalSchema]'s contract, a field this build
     * cannot recognize is inert, never a reason to refuse the record.
     */
    private fun JSONObject.decodeStamp(key: String): VersionStamp? {
        val value = optJSONObject(key) ?: return null
        return when (value.optString("kind")) {
            "composite" -> VersionStamp.Composite(
                sizeBytes = value.optLong("sizeBytes", Long.MIN_VALUE)
                    .takeUnless { it == Long.MIN_VALUE },
                modifiedAtMillis = value.optLong("modifiedAtMillis", Long.MIN_VALUE)
                    .takeUnless { it == Long.MIN_VALUE },
            )
            "revision" -> value.optString("token").takeIf(String::isNotBlank)
                ?.let { VersionStamp.Revision(it) }
            else -> null
        }
    }

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
                                    sourceStamp = item.decodeStamp("sourceStamp"),
                                    destinationStamp = item.decodeStamp("destinationStamp"),
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
                            sourceParentRoot = value.decodeItemRef("sourceParentRoot"),
                            sourceParentSegments = value.decodeSegments("sourceParentSegments"),
                            destinationRoot = value.decodeItemRef("destinationRoot"),
                            destinationSegments = value.decodeSegments("destinationSegments"),
                            undone = value.optBoolean("undone", false),
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
