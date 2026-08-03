package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small durable journal for user-visible file operations.
 *
 * The journal intentionally stores only provider URIs, display names, byte counts, states, and
 * coarse error codes. It never stores file contents, credentials, archive passwords, or AI keys.
 */
class OperationJournal(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<FileOperation> {
        val decoded = decode(preferences.getString(RECORDS_KEY, null))
        val recoveredAt = System.currentTimeMillis()
        val recovered = decoded.map { OperationRecoveryPolicy.recoverAfterProcessDeath(it, recoveredAt) }
        if (recovered != decoded) persist(recovered)
        return recovered.sortedByDescending(FileOperation::updatedAtMillis)
    }

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

    @Synchronized
    fun clearFinished() {
        persist(list().filterNot { OperationRecoveryPolicy.isTerminal(it.state) })
    }

    private fun persist(records: List<FileOperation>) {
        val root = JSONArray()
        records.forEach { operation ->
            val items = JSONArray()
            operation.items.forEach { item ->
                items.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("source", item.source.toString())
                        .put("destination", item.destination?.toString())
                        .put("displayName", item.displayName)
                        .put("expectedBytes", item.expectedBytes)
                        .put("completedBytes", item.completedBytes)
                        .put("state", item.state.name)
                        .put("errorCode", item.errorCode),
                )
            }
            root.put(
                JSONObject()
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
                                    source = Uri.parse(item.getString("source")),
                                    destination = item.optString("destination").takeIf(String::isNotBlank)?.let(Uri::parse),
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
        const val MAX_RECORDS = 200
    }
}
