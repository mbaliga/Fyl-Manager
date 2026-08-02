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
)

/** HTTPS-only WebDAV adapter. Passwords are supplied per call from the encrypted credential vault. */
class WebDavService(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun list(config: WebDavConfig, path: String, password: CharArray): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val url = resolve(config.baseUrl, path)
            val body = """
                <?xml version="1.0" encoding="utf-8" ?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/><d:resourcetype/><d:getcontentlength/>
                    <d:getlastmodified/><d:getcontenttype/>
                  </d:prop>
                </d:propfind>
            """.trimIndent().toRequestBody(XML)
            val request = request(config, password, url)
                .header("Depth", "1")
                .method("PROPFIND", body)
                .build()
            execute(request) { responseBody -> parseMultiStatus(responseBody) }
        }

    suspend fun download(
        config: WebDavConfig,
        path: String,
        password: CharArray,
        destination: File,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val request = request(config, password, resolve(config.baseUrl, path)).get().build()
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
    ) = withContext(Dispatchers.IO) {
        require(source.isFile)
        val request = request(config, password, resolve(config.baseUrl, path))
            .put(source.asRequestBody(contentType.toMediaType()))
            .build()
        execute(request) { Unit }
    }

    suspend fun createDirectory(config: WebDavConfig, path: String, password: CharArray) =
        withContext(Dispatchers.IO) {
            val request = request(config, password, resolve(config.baseUrl, path))
                .method("MKCOL", EMPTY_BODY)
                .build()
            execute(request) { Unit }
        }

    suspend fun delete(config: WebDavConfig, path: String, password: CharArray, confirmed: Boolean) =
        withContext(Dispatchers.IO) {
            require(confirmed) { "Remote deletion requires confirmation." }
            val request = request(config, password, resolve(config.baseUrl, path)).delete().build()
            execute(request) { Unit }
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

    private fun <T> execute(request: Request, transform: (String) -> T): T {
        client.newCall(request).execute().use { response ->
            val body = response.body.string().take(MAX_RESPONSE_CHARS)
            check(response.isSuccessful || response.code == 207) {
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
                    }
                    "href" -> href = parser.nextText()
                    "displayname" -> displayName = parser.nextText()
                    "collection" -> directory = true
                    "getcontentlength" -> size = parser.nextText().toLongOrNull()
                    "getlastmodified" -> modified = parser.nextText()
                    "getcontenttype" -> contentType = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name.equals("response", true)) {
                href?.let {
                    results += RemoteEntry(
                        href = it,
                        name = displayName?.ifBlank { null } ?: Uri.decode(it.trimEnd('/').substringAfterLast('/')),
                        directory = directory,
                        sizeBytes = size,
                        modified = modified,
                        contentType = contentType,
                    )
                }
            }
            event = parser.next()
        }
        return results
    }

    private fun resolve(baseUrl: String, path: String): String =
        URI(baseUrl.trimEnd('/') + "/").resolve(path.trimStart('/')).toString()

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
        const val MAX_RESPONSE_CHARS = 2_000_000
    }
}
