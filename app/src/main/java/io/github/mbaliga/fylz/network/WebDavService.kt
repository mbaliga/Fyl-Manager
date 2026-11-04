package io.github.mbaliga.fylz.network

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.URI
import kotlin.coroutines.coroutineContext

data class WebDavConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val username: String,
)

data class RemoteEntry(
    val href: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long?,
    val modified: String?,
    val contentType: String?,
    val etag: String? = null,
)

/** The current state of one WebDAV resource, read immediately before a destructive request. */
data class WebDavResourceState(
    val collection: Boolean,
    val etag: String?,
    val children: List<RemoteObject>,
)

/** HTTPS-only WebDAV adapter. Passwords are supplied per call from the encrypted credential vault. */
class WebDavService(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun list(config: WebDavConfig, path: String, password: CharArray): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val url = WebDavPaths.resolve(config.baseUrl, path, collection = true)
            val body = PROPFIND_BODY.toRequestBody(XML)
            val request = request(config, password, url)
                .header("Depth", "1")
                .method("PROPFIND", body)
                .build()
            // A Depth: 1 PROPFIND also describes the folder itself; that is not one of its children.
            execute(request) { responseBody ->
                parseMultiStatus(responseBody).filterNot { WebDavPaths.isSameResource(url, it.href) }
            }
        }

    /** Type, ETag and children of [path] itself, from one Depth: 1 PROPFIND. */
    suspend fun inspect(config: WebDavConfig, path: String, password: CharArray): WebDavResourceState =
        withContext(Dispatchers.IO) {
            val url = WebDavPaths.resolve(config.baseUrl, path)
            val request = request(config, password, url)
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
                .build()
            execute(request) { body ->
                val entries = parseMultiStatus(body)
                val self = entries.firstOrNull { WebDavPaths.isSameResource(url, it.href) }
                    ?: error("The server didn’t describe “${path.substringAfterLast('/')}”, so Fylz won’t delete it.")
                WebDavResourceState(
                    collection = self.directory,
                    etag = self.etag,
                    children = entries.filterNot { it === self }.map { child ->
                        RemoteObject(child.href, child.name, child.directory, child.sizeBytes, null, null, child.etag)
                    },
                )
            }
        }

    suspend fun download(
        config: WebDavConfig,
        path: String,
        password: CharArray,
        destination: File,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val request = request(config, password, WebDavPaths.resolve(config.baseUrl, path)).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "WebDAV download failed with HTTP ${response.code}." }
            val body = response.body
            val total = body.contentLength().takeIf { it >= 0L }
            val partial = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.part")
            try {
                var completed = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            completed += count
                            onProgress(completed, total)
                        }
                        output.fd.sync()
                    }
                }
                check(partial.renameTo(destination)) { "Unable to store the downloaded file." }
            } finally {
                partial.delete()
            }
        }
    }

    suspend fun upload(
        config: WebDavConfig,
        path: String,
        password: CharArray,
        source: File,
        contentType: String = "application/octet-stream",
        overwrite: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        require(source.isFile)
        val request = request(config, password, WebDavPaths.resolve(config.baseUrl, path))
            .put(source.asRequestBody(contentType.toMediaType()))
            .apply { if (!overwrite) header("If-None-Match", "*") }
            .build()
        execute(
            request,
            preconditionFailed = { RemoteConflictException(path.substringAfterLast('/'), existingIsDirectory = false) },
        ) { Unit }
    }

    suspend fun createDirectory(config: WebDavConfig, path: String, password: CharArray) =
        withContext(Dispatchers.IO) {
            val request = request(config, password, WebDavPaths.resolve(config.baseUrl, path, collection = true))
                .method("MKCOL", EMPTY_BODY)
                .build()
            execute(request) { Unit }
        }

    suspend fun delete(
        config: WebDavConfig,
        path: String,
        password: CharArray,
        confirmed: Boolean,
        collection: Boolean = false,
        ifMatch: String? = null,
    ) = withContext(Dispatchers.IO) {
        require(confirmed) { "Remote deletion requires confirmation." }
        require(RemotePathPolicy.normalize(path).isNotBlank()) { "The connection’s top-level folder can’t be deleted." }
        val request = request(config, password, WebDavPaths.resolve(config.baseUrl, path, collection))
            .delete()
            .apply { if (ifMatch != null) header("If-Match", ifMatch) }
            .build()
        // 207 Multi-Status on DELETE means some members could not be removed: that is a failure.
        execute(
            request,
            acceptMultiStatus = false,
            preconditionFailed = {
                IllegalStateException("“${path.substringAfterLast('/')}” changed on the server since it was checked, so it was not deleted.")
            },
        ) { Unit }
    }

    private fun request(
        config: WebDavConfig,
        password: CharArray,
        url: String,
    ): Request.Builder {
        validateBase(config.baseUrl)
        return try {
            Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(config.username, password.concatToString()))
                .header("User-Agent", "Fylz/1")
        } finally {
            password.fill('\u0000')
        }
    }

    private fun <T> execute(
        request: Request,
        acceptMultiStatus: Boolean = true,
        preconditionFailed: (() -> Throwable)? = null,
        transform: (String) -> T,
    ): T {
        client.newCall(request).execute().use { response ->
            val body = response.body.string().take(MAX_RESPONSE_CHARS)
            if (response.code == 412 && preconditionFailed != null) throw preconditionFailed()
            check(if (response.code == 207) acceptMultiStatus else response.isSuccessful) {
                "WebDAV request failed with HTTP ${response.code}. ${body.take(300)}"
            }
            return transform(body)
        }
    }

    private fun parseMultiStatus(xml: String): List<RemoteEntry> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }
        val results = mutableListOf<RemoteEntry>()
        var href: String? = null
        var displayName: String? = null
        var directory = false
        var size: Long? = null
        var modified: String? = null
        var contentType: String? = null
        var etag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "response" -> {
                        href = null
                        displayName = null
                        directory = false
                        size = null
                        modified = null
                        contentType = null
                        etag = null
                    }
                    "href" -> href = parser.nextText()
                    "displayname" -> displayName = parser.nextText()
                    "collection" -> directory = true
                    "getcontentlength" -> size = parser.nextText().toLongOrNull()
                    "getlastmodified" -> modified = parser.nextText()
                    "getcontenttype" -> contentType = parser.nextText()
                    "getetag" -> etag = parser.nextText().trim().ifEmpty { null }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name.equals("response", true)) {
                href?.let {
                    results += RemoteEntry(
                        href = it,
                        // The path segment from href, never displayname: displayname is free text a
                        // server may set to anything, and paths built from it would address the wrong
                        // resource (or none) when browsing, downloading or deleting.
                        name = Uri.decode(it.trimEnd('/').substringAfterLast('/')),
                        directory = directory,
                        sizeBytes = size,
                        modified = modified,
                        contentType = contentType,
                        etag = etag,
                    )
                }
            }
            event = parser.next()
        }
        return results
    }

    private fun validateBase(baseUrl: String) {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "WebDAV servers must use HTTPS."
        }
        require(uri.userInfo == null) { "Credentials must not be embedded in the URL." }
    }

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/><d:resourcetype/><d:getcontentlength/>
                <d:getlastmodified/><d:getcontenttype/><d:getetag/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
        const val MAX_RESPONSE_CHARS = 2_000_000
    }
}

/**
 * Turns a connection-relative path into a URL under the configured WebDAV base, and nowhere else.
 *
 * The path is normalised (no `.`/`..`, no control characters) and every segment is percent-encoded,
 * so a name such as `a b`, `x?y`, `%2e%2e` or `https://other.host/x` stays a literal file name under
 * the base instead of becoming a query, a traversal or a different host. The result is then
 * checked against the base's scheme, host, port and path prefix before any request (and its Basic
 * credentials) is built. Previously the raw path went through `URI.resolve`.
 */
internal object WebDavPaths {
    fun resolve(baseUrl: String, path: String, collection: Boolean = false): String {
        val base = URI(baseUrl.trimEnd('/') + "/")
        val segments = RemotePathPolicy.normalize(path).split('/').filter(String::isNotEmpty)
        val encoded = segments.joinToString("/") { encodeSegment(it) }
        val suffix = if (collection && encoded.isNotEmpty()) "/" else ""
        val resolved = URI(base.toString() + encoded + suffix)
        require(
            resolved.scheme == base.scheme &&
                resolved.host.equals(base.host, ignoreCase = true) &&
                resolved.port == base.port &&
                resolved.rawQuery == null &&
                resolved.rawFragment == null &&
                (resolved.rawPath ?: "").startsWith(base.rawPath ?: "/"),
        ) { "That remote path is outside this connection." }
        return resolved.toString()
    }

    /** True when [href] (absolute URL or absolute path, as servers send it) names the same resource as [url]. */
    fun isSameResource(url: String, href: String): Boolean {
        val target = URI(url)
        val other = runCatching { target.resolve(URI(href)) }.getOrNull() ?: return false
        return decodedPath(other) == decodedPath(target)
    }

    private fun decodedPath(uri: URI): String = (uri.path ?: "").trimEnd('/')

    private fun encodeSegment(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        return buildString {
            for (byte in bytes) {
                val c = byte.toInt() and 0xFF
                if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar())
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
    }
}
