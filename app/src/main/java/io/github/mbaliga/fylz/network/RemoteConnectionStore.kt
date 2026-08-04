package io.github.mbaliga.fylz.network

import android.content.Context
import io.github.mbaliga.fylz.ai.ApiKeyVault
import org.json.JSONArray
import org.json.JSONObject

/** Which backend a saved connection uses. */
enum class RemoteKind(val label: String) {
    WEBDAV("WebDAV"),
    SFTP("SFTP"),
    SMB("SMB / Windows share"),
    S3("S3-compatible"),
}

/**
 * A saved network location.
 *
 * Deliberately holds **no secret**. The password / secret access key lives in [ApiKeyVault],
 * encrypted with an Android Keystore key, under the connection's [id]. PRODUCT_BRIEF.md's
 * non-negotiable principles require that passwords and API keys are never logged and never stored
 * in plaintext, and splitting the record this way makes that structural rather than a convention.
 */
data class RemoteConnection(
    val id: String,
    val kind: RemoteKind,
    val displayName: String,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    /** SMB share name. */
    val share: String = "",
    /** SMB domain. */
    val domain: String = "",
    /** WebDAV base URL / S3 endpoint. */
    val endpoint: String = "",
    /** S3 region. */
    val region: String = "",
    /** S3 bucket. */
    val bucket: String = "",
    /** Mandatory SHA-256 host-key fingerprint for SFTP. */
    val hostKeyFingerprint: String = "",
    val writesEnabled: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("displayName", displayName)
        put("host", host)
        put("port", port)
        put("username", username)
        put("share", share)
        put("domain", domain)
        put("endpoint", endpoint)
        put("region", region)
        put("bucket", bucket)
        put("hostKeyFingerprint", hostKeyFingerprint)
        put("writesEnabled", writesEnabled)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteConnection? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val kind = runCatching { RemoteKind.valueOf(json.optString("kind")) }.getOrNull()
                ?: return null
            return RemoteConnection(
                id = id,
                kind = kind,
                displayName = json.optString("displayName", id),
                host = json.optString("host"),
                port = json.optInt("port"),
                username = json.optString("username"),
                share = json.optString("share"),
                domain = json.optString("domain"),
                endpoint = json.optString("endpoint"),
                region = json.optString("region"),
                bucket = json.optString("bucket"),
                hostKeyFingerprint = json.optString("hostKeyFingerprint"),
                writesEnabled = json.optBoolean("writesEnabled", false),
            )
        }
    }
}

/**
 * Persists the user's network locations.
 *
 * Before this existed, `SftpProvider`, `SmbProvider` and `S3RemoteProvider` were fully implemented
 * and unit-tested but had **zero UI references** -- only WebDAV was reachable, through a one-shot
 * dialog that forgot everything you typed. This is the missing half.
 */
class RemoteConnectionStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val vault = ApiKeyVault(context.applicationContext)

    fun list(): List<RemoteConnection> {
        val raw = preferences.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { index -> array.optJSONObject(index)?.let(RemoteConnection::fromJson) }
            .sortedBy { it.displayName.lowercase() }
    }

    /** Saves [connection], replacing any existing record with the same id, plus its secret. */
    fun save(connection: RemoteConnection, secret: CharArray?) {
        val updated = list().filterNot { it.id == connection.id } + connection
        persist(updated)
        if (secret != null && secret.isNotEmpty()) {
            // ApiKeyVault.save zeroes the array it is handed.
            vault.save(secretKeyFor(connection.id), secret)
        }
    }

    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
        runCatching { vault.remove(secretKeyFor(id)) }
    }

    /** Returns a fresh copy of the stored secret, or null when none was saved. */
    fun secret(id: String): CharArray? = runCatching { vault.read(secretKeyFor(id)) }.getOrNull()

    fun hasSecret(id: String): Boolean = vault.has(secretKeyFor(id))

    private fun persist(connections: List<RemoteConnection>) {
        val array = JSONArray()
        connections.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }

    private fun secretKeyFor(id: String): String = "$SECRET_PREFIX$id"

    companion object {
        private const val PREFERENCES_NAME = "fylz-remote-connections"
        private const val KEY_CONNECTIONS = "connections"
        private const val SECRET_PREFIX = "remote:"
    }
}
