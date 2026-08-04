package io.github.mbaliga.fylz.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class BackupStore(context: Context) {
    private val root = File(context.filesDir, "backup-state")
    private val plansFile = File(root, "plans.json")
    private val runsFile = File(root, "runs.json")
    private val snapshotsFile = File(root, "snapshots.json")
    private val mediaFile = File(root, "media-state.json")
    private val leasesFile = File(root, "leases.json")

    fun plans(): List<BackupPlan> = synchronized(GLOBAL_LOCK) {
        readArray(plansFile).mapNotNull(::decodePlan).sortedBy(BackupPlan::name)
    }

    fun plan(id: String): BackupPlan? = plans().firstOrNull { it.id == id }

    fun putPlan(plan: BackupPlan) = synchronized(GLOBAL_LOCK) {
        val plans = plans().filterNot { it.id == plan.id }.toMutableList()
        plans += plan.copy(updatedAtMillis = System.currentTimeMillis())
        writeArray(plansFile, plans.sortedBy(BackupPlan::name).map(::encodePlan))
    }

    fun removePlan(id: String): Boolean = synchronized(GLOBAL_LOCK) {
        if (snapshots(id).isNotEmpty()) return@synchronized false
        writeArray(plansFile, plans().filterNot { it.id == id }.map(::encodePlan))
        writeArray(runsFile, runs().filterNot { it.planId == id }.map(::encodeRun))
        val allMedia = readMediaStates().toMutableMap().apply { remove(id) }
        writeMediaStates(allMedia)
        val leases = readLeases().toMutableMap().apply { remove(id) }
        writeLeases(leases)
        true
    }

    fun runs(planId: String? = null): List<BackupRunRecord> = synchronized(GLOBAL_LOCK) {
        readArray(runsFile).mapNotNull(::decodeRun)
            .filter { planId == null || it.planId == planId }
            .sortedByDescending(BackupRunRecord::startedAtMillis)
    }

    fun putRun(run: BackupRunRecord) = synchronized(GLOBAL_LOCK) {
        val runs = runs().filterNot { it.id == run.id }.toMutableList()
        runs += run
        writeArray(runsFile, runs.sortedByDescending(BackupRunRecord::startedAtMillis).take(500).map(::encodeRun))
    }

    fun snapshots(planId: String? = null): List<BackupSnapshotRecord> = synchronized(GLOBAL_LOCK) {
        readArray(snapshotsFile).mapNotNull(::decodeSnapshot)
            .filter { planId == null || it.planId == planId }
            .sortedByDescending(BackupSnapshotRecord::createdAtMillis)
    }

    fun putSnapshot(snapshot: BackupSnapshotRecord) = synchronized(GLOBAL_LOCK) {
        val snapshots = snapshots().filterNot { it.id == snapshot.id }.toMutableList()
        snapshots += snapshot
        writeArray(snapshotsFile, snapshots.sortedByDescending(BackupSnapshotRecord::createdAtMillis).map(::encodeSnapshot))
    }

    fun removeSnapshot(id: String) = synchronized(GLOBAL_LOCK) {
        writeArray(snapshotsFile, snapshots().filterNot { it.id == id }.map(::encodeSnapshot))
    }

    fun mediaState(planId: String): BackupMediaState = synchronized(GLOBAL_LOCK) {
        readMediaStates()[planId] ?: BackupMediaState()
    }

    fun putMediaState(planId: String, state: BackupMediaState) = synchronized(GLOBAL_LOCK) {
        val states = readMediaStates().toMutableMap()
        states[planId] = state
        writeMediaStates(states)
    }

    fun acquireLease(planId: String, ownerId: String = UUID.randomUUID().toString()): String? = synchronized(GLOBAL_LOCK) {
        val now = System.currentTimeMillis()
        val leases = readLeases().filterValues { now - it.acquiredAtMillis < LEASE_TIMEOUT_MILLIS }.toMutableMap()
        if (leases.containsKey(planId)) return@synchronized null
        leases[planId] = BackupLease(ownerId, now)
        writeLeases(leases)
        ownerId
    }

    fun refreshLease(planId: String, ownerId: String): Boolean = synchronized(GLOBAL_LOCK) {
        val leases = readLeases().toMutableMap()
        val current = leases[planId] ?: return@synchronized false
        if (current.ownerId != ownerId) return@synchronized false
        leases[planId] = current.copy(acquiredAtMillis = System.currentTimeMillis())
        writeLeases(leases)
        true
    }

    fun releaseLease(planId: String, ownerId: String) = synchronized(GLOBAL_LOCK) {
        val leases = readLeases().toMutableMap()
        if (leases[planId]?.ownerId == ownerId) {
            leases.remove(planId)
            writeLeases(leases)
        }
    }

    private fun readMediaStates(): Map<String, BackupMediaState> = runCatching {
        if (!mediaFile.isFile) return emptyMap()
        val root = JSONObject(mediaFile.readText())
        root.keys().asSequence().associateWith { key ->
            val value = root.getJSONObject(key)
            BackupMediaState(
                initialized = value.optBoolean("initialized", false),
                knownMediaUris = value.optJSONArray("knownMediaUris")?.toStringSet().orEmpty(),
                pendingNewMedia = value.optInt("pendingNewMedia", 0),
                lastScanAtMillis = value.optLong("lastScanAtMillis", 0L),
            )
        }
    }.getOrElse { emptyMap() }

    private fun writeMediaStates(states: Map<String, BackupMediaState>) {
        val root = JSONObject()
        states.forEach { (planId, state) ->
            root.put(planId, JSONObject().apply {
                put("initialized", state.initialized)
                put("knownMediaUris", JSONArray(state.knownMediaUris.toList()))
                put("pendingNewMedia", state.pendingNewMedia)
                put("lastScanAtMillis", state.lastScanAtMillis)
            })
        }
        atomicWrite(mediaFile, root.toString())
    }

    private fun readLeases(): Map<String, BackupLease> = runCatching {
        if (!leasesFile.isFile) return emptyMap()
        val root = JSONObject(leasesFile.readText())
        root.keys().asSequence().associateWith { key ->
            val value = root.getJSONObject(key)
            BackupLease(value.getString("ownerId"), value.getLong("acquiredAtMillis"))
        }
    }.getOrElse { emptyMap() }

    private fun writeLeases(leases: Map<String, BackupLease>) {
        val root = JSONObject()
        leases.forEach { (planId, lease) ->
            root.put(planId, JSONObject().apply {
                put("ownerId", lease.ownerId)
                put("acquiredAtMillis", lease.acquiredAtMillis)
            })
        }
        atomicWrite(leasesFile, root.toString())
    }

    private fun encodePlan(value: BackupPlan) = JSONObject().apply {
        put("id", value.id)
        put("name", value.name)
        put("sourceTreeUri", value.sourceTreeUri)
        put("destinationTreeUri", value.destinationTreeUri)
        put("enabled", value.enabled)
        put("retentionCount", value.retentionCount)
        put("createdAtMillis", value.createdAtMillis)
        put("updatedAtMillis", value.updatedAtMillis)
        put("schedule", JSONObject().apply {
            put("dailyEnabled", value.schedule.dailyEnabled)
            put("dailyHour", value.schedule.dailyHour)
            put("dailyMinute", value.schedule.dailyMinute)
            put("mediaCountEnabled", value.schedule.mediaCountEnabled)
            put("mediaThreshold", value.schedule.mediaThreshold)
            put("mediaScanIntervalMinutes", value.schedule.mediaScanIntervalMinutes)
        })
        put("conditions", JSONObject().apply {
            put("requiresCharging", value.conditions.requiresCharging)
            put("requiresDeviceIdle", value.conditions.requiresDeviceIdle)
            put("requiresBatteryNotLow", value.conditions.requiresBatteryNotLow)
            put("requiresStorageNotLow", value.conditions.requiresStorageNotLow)
            put("network", value.conditions.network.name)
        })
    }

    private fun decodePlan(value: JSONObject): BackupPlan? = runCatching {
        val schedule = value.optJSONObject("schedule") ?: JSONObject()
        val conditions = value.optJSONObject("conditions") ?: JSONObject()
        BackupPlan(
            id = value.getString("id"),
            name = value.getString("name"),
            sourceTreeUri = value.getString("sourceTreeUri"),
            destinationTreeUri = value.getString("destinationTreeUri"),
            enabled = value.optBoolean("enabled", false),
            retentionCount = value.optInt("retentionCount", 5),
            schedule = BackupSchedule(
                dailyEnabled = schedule.optBoolean("dailyEnabled", false),
                dailyHour = schedule.optInt("dailyHour", 2),
                dailyMinute = schedule.optInt("dailyMinute", 0),
                mediaCountEnabled = schedule.optBoolean("mediaCountEnabled", false),
                mediaThreshold = schedule.optInt("mediaThreshold", 25),
                mediaScanIntervalMinutes = schedule.optInt("mediaScanIntervalMinutes", 60),
            ),
            conditions = BackupConditions(
                requiresCharging = conditions.optBoolean("requiresCharging", false),
                requiresDeviceIdle = conditions.optBoolean("requiresDeviceIdle", false),
                requiresBatteryNotLow = conditions.optBoolean("requiresBatteryNotLow", true),
                requiresStorageNotLow = conditions.optBoolean("requiresStorageNotLow", true),
                network = BackupNetworkConstraint.valueOf(conditions.optString("network", BackupNetworkConstraint.NONE.name)),
            ),
            createdAtMillis = value.optLong("createdAtMillis", System.currentTimeMillis()),
            updatedAtMillis = value.optLong("updatedAtMillis", System.currentTimeMillis()),
        )
    }.getOrNull()

    private fun encodeRun(value: BackupRunRecord) = JSONObject().apply {
        put("id", value.id)
        put("planId", value.planId)
        put("trigger", value.trigger.name)
        put("status", value.status.name)
        put("startedAtMillis", value.startedAtMillis)
        put("completedAtMillis", value.completedAtMillis ?: JSONObject.NULL)
        put("message", value.message ?: JSONObject.NULL)
        put("snapshotId", value.snapshotId ?: JSONObject.NULL)
    }

    private fun decodeRun(value: JSONObject): BackupRunRecord? = runCatching {
        BackupRunRecord(
            id = value.getString("id"),
            planId = value.getString("planId"),
            trigger = BackupTrigger.valueOf(value.getString("trigger")),
            status = BackupRunStatus.valueOf(value.getString("status")),
            startedAtMillis = value.getLong("startedAtMillis"),
            completedAtMillis = value.optLongOrNull("completedAtMillis"),
            message = value.optStringOrNull("message"),
            snapshotId = value.optStringOrNull("snapshotId"),
        )
    }.getOrNull()

    private fun encodeSnapshot(value: BackupSnapshotRecord) = JSONObject().apply {
        put("id", value.id)
        put("planId", value.planId)
        put("snapshotTreeUri", value.snapshotTreeUri)
        put("displayName", value.displayName)
        put("sourceDisplayName", value.sourceDisplayName)
        put("createdAtMillis", value.createdAtMillis)
        put("fileCount", value.fileCount)
        put("directoryCount", value.directoryCount)
        put("totalBytes", value.totalBytes)
        put("manifestSha256", value.manifestSha256)
    }

    private fun decodeSnapshot(value: JSONObject): BackupSnapshotRecord? = runCatching {
        BackupSnapshotRecord(
            id = value.getString("id"),
            planId = value.getString("planId"),
            snapshotTreeUri = value.getString("snapshotTreeUri"),
            displayName = value.getString("displayName"),
            sourceDisplayName = value.getString("sourceDisplayName"),
            createdAtMillis = value.getLong("createdAtMillis"),
            fileCount = value.getInt("fileCount"),
            directoryCount = value.getInt("directoryCount"),
            totalBytes = value.getLong("totalBytes"),
            manifestSha256 = value.getString("manifestSha256"),
        )
    }.getOrNull()

    private fun readArray(file: File): List<JSONObject> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        List(array.length()) { array.getJSONObject(it) }
    }.getOrElse { emptyList() }

    private fun writeArray(file: File, values: List<JSONObject>) {
        val array = JSONArray()
        values.forEach(array::put)
        atomicWrite(file, array.toString())
    }

    private fun atomicWrite(file: File, value: String) {
        root.mkdirs()
        val temp = File(root, ".${file.name}.tmp")
        temp.writeText(value)
        val previous = File(root, ".${file.name}.previous")
        previous.delete()
        if (file.exists() && !file.renameTo(previous)) error("Unable to preserve backup metadata.")
        if (!temp.renameTo(file)) {
            previous.renameTo(file)
            error("Unable to commit backup metadata.")
        }
        previous.delete()
    }

    private fun JSONArray.toStringSet(): Set<String> = buildSet {
        repeat(length()) { index -> add(getString(index)) }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private data class BackupLease(val ownerId: String, val acquiredAtMillis: Long)

    private companion object {
        val GLOBAL_LOCK = Any()
        const val LEASE_TIMEOUT_MILLIS = 6L * 60L * 60L * 1000L
    }
}
