package io.github.mbaliga.fylz.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

data class S3CompatibleConfig(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val pathStyle: Boolean = true,
) {
    init {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Object storage must use HTTPS." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(region.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(bucket.matches(Regex("[a-zA-Z0-9._-]{1,255}")))
        require(accessKeyId.matches(Regex("[A-Za-z0-9/+=._-]{1,256}")))
    }
}

/** Minimal S3-compatible provider using AWS Signature Version 4 and path-safe object keys. */
class S3CompatibleProvider(
    override val id: String,
    override val displayName: String,
    private val config: S3CompatibleConfig,
    secretAccessKey: CharArray,
    private val sessionToken: String? = null,
    private val client: OkHttpClient = OkHttpClient(),
) : RemoteProvider, AutoCloseable {
    private val secret = secretAccessKey.copyOf()

    override val capabilities = setOf(
        RemoteCapability.LIST,
        RemoteCapability.DOWNLOAD,
        RemoteCapability.UPLOAD,
        RemoteCapability.DELETE,
        RemoteCapability.RANGE_READ,
        RemoteCapability.CHECKSUM,
    )

    override suspend fun list(path: String, continuationToken: String?, limit: Int): RemotePage = withContext(Dispatchers.IO) {
        require(limit in 1..1_000)
        val prefix = RemotePathPolicy.normalize(path).let { if (it.isBlank()) "" else "$it/" }
        val builder = baseUrl("").newBuilder()
            .addQueryParameter("list-type", "2")
            .addQueryParameter("delimiter", "/")
            .addQueryParameter("prefix", prefix)
            .addQueryParameter("max-keys", limit.toString())
        continuationToken?.let { builder.addQueryParameter("continuation-token", it) }
        val request = signedRequest("GET", builder.build(), EMPTY_SHA256, null).build()
        execute(request) { body -> parseList(body, prefix) }
    }

    override suspend fun download(path: String, destination: File, onProgress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        val key = RemotePathPolicy.normalize(path)
        require(key.isNotBlank())
        val request = signedRequest("GET", baseUrl(key), EMPTY_SHA256, null).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Object download failed with HTTP ${response.code}." }
            val total = response.body.contentLength().takeIf { it >= 0L }
            val partial = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.part")
            try {
                var completed = 0L
                response.body.byteStream().use { input ->
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
                check(partial.renameTo(destination)) { "Unable to commit downloaded object." }
            } finally {
                partial.delete()
            }
        }
    }

    override suspend fun upload(path: String, source: File, mimeType: String) = withContext(Dispatchers.IO) {
        require(source.isFile)
        val key = RemotePathPolicy.normalize(path)
        require(key.isNotBlank())
        val payloadHash = sha256(source)
        val body = source.asRequestBody(mimeType.toMediaType())
        val request = signedRequest("PUT", baseUrl(key), payloadHash, mimeType)
            .put(body)
            .build()
        execute(request) { Unit }
    }

    override suspend fun createDirectory(path: String) {
        val normalized = RemotePathPolicy.normalize(path).trimEnd('/')
        require(normalized.isNotBlank())
        val empty = ByteArray(0).toRequestBody("application/x-directory".toMediaType())
        val request = signedRequest("PUT", baseUrl("$normalized/"), EMPTY_SHA256, "application/x-directory")
            .put(empty)
            .build()
        withContext(Dispatchers.IO) { execute(request) { Unit } }
    }

    override suspend fun delete(path: String, confirmed: Boolean) = withContext(Dispatchers.IO) {
        require(confirmed) { "Deleting a remote object requires confirmation." }
        val key = RemotePathPolicy.normalize(path)
        require(key.isNotBlank())
        val request = signedRequest("DELETE", baseUrl(key), EMPTY_SHA256, null).delete().build()
        execute(request) { Unit }
    }

    override fun close() { secret.fill('\u0000') }

    private fun signedRequest(method: String, url: HttpUrl, payloadHash: String, contentType: String?): Request.Builder {
        val now = Instant.now()
        val date = DATE.format(now)
        val timestamp = TIMESTAMP.format(now)
        val canonicalUri = url.encodedPath
        val canonicalQuery = url.queryParameterNames.sorted().flatMap { name ->
            url.queryParameterValues(name).sorted().map { value -> "${awsEncode(name)}=${awsEncode(value)}" }
        }.joinToString("&")
        val host = url.host + if (url.port != url.defaultPort()) ":${url.port}" else ""
        val headers = sortedMapOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to timestamp)
        sessionToken?.let { headers["x-amz-security-token"] = it }
        contentType?.let { headers["content-type"] = it }
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalRequest = listOf(method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
        val scope = "$date/${config.region}/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$timestamp\n$scope\n${sha256(canonicalRequest.toByteArray())}"
        val signingKey = hmac(hmac(hmac(hmac(("AWS4" + secret.concatToString()).toByteArray(), date), config.region), "s3"), "aws4_request")
        val signature = hmac(signingKey, stringToSign).toHex()
        val authorization = "AWS4-HMAC-SHA256 Credential=${config.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        signingKey.fill(0)
        return Request.Builder().url(url).header("Authorization", authorization).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }
    }

    private fun baseUrl(key: String): HttpUrl {
        val endpoint = config.endpoint.trimEnd('/').toHttpUrl()
        return if (config.pathStyle) {
            endpoint.newBuilder().addPathSegment(config.bucket).apply {
                key.split('/').filter(String::isNotEmpty).forEach(::addPathSegment)
                if (key.endsWith('/')) addPathSegment("")
            }.build()
        } else {
            endpoint.newBuilder().host("${config.bucket}.${endpoint.host}").apply {
                key.split('/').filter(String::isNotEmpty).forEach(::addPathSegment)
                if (key.endsWith('/')) addPathSegment("")
            }.build()
        }
    }

    private fun <T> execute(request: Request, transform: (String) -> T): T {
        client.newCall(request).execute().use { response ->
            val text = response.body.string().take(MAX_RESPONSE_CHARS)
            check(response.isSuccessful) { "Object storage request failed with HTTP ${response.code}. ${text.take(300)}" }
            return transform(text)
        }
    }

    private fun parseList(xml: String, prefix: String): RemotePage {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(xml.reader()) }
        val entries = mutableListOf<RemoteObject>()
        var key: String? = null; var size: Long? = null; var modified: Long? = null; var etag: String? = null
        var commonPrefix: String? = null; var token: String? = null; var container: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Contents", "CommonPrefixes" -> { container = parser.name; key = null; commonPrefix = null; size = null; modified = null; etag = null }
                    "Key" -> key = parser.nextText()
                    "Prefix" -> if (container == "CommonPrefixes") commonPrefix = parser.nextText()
                    "Size" -> size = parser.nextText().toLongOrNull()
                    "LastModified" -> modified = runCatching { Instant.parse(parser.nextText()).toEpochMilli() }.getOrNull()
                    "ETag" -> etag = parser.nextText().trim('"')
                    "NextContinuationToken" -> token = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "Contents" -> key?.takeIf { it != prefix }?.let { value -> entries += RemoteObject(value, value.removePrefix(prefix).substringAfterLast('/'), false, size, modified, null, etag) }
                    "CommonPrefixes" -> commonPrefix?.let { value -> entries += RemoteObject(value, value.removePrefix(prefix).trimEnd('/').substringAfterLast('/'), true, null, null, null) }
                }
                if (parser.name == container) container = null
            }
            event = parser.next()
        }
        return RemotePage(entries.distinctBy(RemoteObject::key), token)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray()) }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun awsEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20").replace("%7E", "~")

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val MAX_RESPONSE_CHARS = 4_000_000
    }
}
