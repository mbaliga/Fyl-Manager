package io.github.mbaliga.fylz.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
        require(displayName.isNotBlank())
        require(region.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(bucket.matches(Regex("[A-Za-z0-9._-]{1,255}")))
        require(accessKeyId.isNotBlank() && accessKeyId.length <= 256)
    }
}

data class ObjectEntry(
    val key: String,
    val sizeBytes: Long?,
    val lastModified: String?,
    val etag: String?,
    val directoryPrefix: Boolean = false,
)

data class ObjectListPage(
    val entries: List<ObjectEntry>,
    val nextContinuationToken: String?,
    val truncated: Boolean,
)

/** HTTPS-only AWS Signature Version 4 adapter for S3-compatible object stores. */
class S3CompatibleService(
    private val client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun list(
        config: S3CompatibleConfig,
        prefix: String = "",
        delimiter: String? = "/",
        continuationToken: String? = null,
        secretAccessKey: CharArray,
    ): ObjectListPage = withContext(Dispatchers.IO) {
        require(prefix.length <= MAX_KEY_CHARS)
        val builder = objectUrl(config, null).newBuilder()
            .addQueryParameter("list-type", "2")
            .addQueryParameter("max-keys", MAX_LIST_RESULTS.toString())
        if (prefix.isNotEmpty()) builder.addQueryParameter("prefix", prefix)
        if (!delimiter.isNullOrEmpty()) builder.addQueryParameter("delimiter", delimiter)
        if (!continuationToken.isNullOrEmpty()) builder.addQueryParameter("continuation-token", continuationToken)
        val request = signedRequest(config, builder.build(), "GET", EMPTY_SHA256, secretAccessKey).build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string().take(MAX_XML_CHARS)
            check(response.isSuccessful) { "Object listing failed with HTTP ${response.code}. ${safeError(body)}" }
            parseList(body)
        }
    }

    suspend fun download(
        config: S3CompatibleConfig,
        key: String,
        destination: File,
        secretAccessKey: CharArray,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        validateKey(key)
        val request = signedRequest(config, objectUrl(config, key), "GET", EMPTY_SHA256, secretAccessKey).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Object download failed with HTTP ${response.code}." }
            val expected = response.body.contentLength().takeIf { it >= 0L }
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
                            onProgress(completed, expected)
                        }
                        output.fd.sync()
                    }
                }
                if (expected != null) check(completed == expected) { "The object download was incomplete." }
                if (destination.exists()) check(destination.delete()) { "Unable to replace the local destination." }
                check(partial.renameTo(destination)) { "Unable to commit the downloaded object." }
            } finally {
                partial.delete()
            }
        }
    }

    suspend fun upload(
        config: S3CompatibleConfig,
        key: String,
        source: File,
        secretAccessKey: CharArray,
        contentType: String = "application/octet-stream",
    ) = withContext(Dispatchers.IO) {
        validateKey(key)
        require(source.isFile && source.canRead())
        val payloadHash = sha256(source)
        val body = source.asRequestBody(contentType.toMediaTypeOrNull())
        val request = signedRequest(config, objectUrl(config, key), "PUT", payloadHash, secretAccessKey)
            .put(body)
            .build()
        client.newCall(request).execute().use { response ->
            val error = response.body.string().take(2_000)
            check(response.isSuccessful) { "Object upload failed with HTTP ${response.code}. ${safeError(error)}" }
        }
    }

    suspend fun delete(
        config: S3CompatibleConfig,
        key: String,
        secretAccessKey: CharArray,
        confirmed: Boolean,
    ) = withContext(Dispatchers.IO) {
        require(confirmed) { "Remote object deletion requires confirmation." }
        validateKey(key)
        val request = signedRequest(config, objectUrl(config, key), "DELETE", EMPTY_SHA256, secretAccessKey)
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful || response.code == 204) { "Object deletion failed with HTTP ${response.code}." }
        }
    }

    private fun signedRequest(
        config: S3CompatibleConfig,
        url: HttpUrl,
        method: String,
        payloadHash: String,
        secretAccessKey: CharArray,
    ): Request.Builder {
        validate(config)
        val now = Date(nowMillis())
        val date = DATE.format(now)
        val timestamp = TIMESTAMP.format(now)
        val canonicalHeaders = "host:${url.host}${portSuffix(url)}\n" +
            "x-amz-content-sha256:$payloadHash\n" +
            "x-amz-date:$timestamp\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = listOf(
            method,
            canonicalUri(url),
            canonicalQuery(url),
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val scope = "$date/${config.region}/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$timestamp\n$scope\n${sha256(canonicalRequest.toByteArray())}"
        val secretBytes = secretAccessKey.concatToString().toByteArray(Charsets.UTF_8)
        return try {
            val dateKey = hmac(("AWS4".toByteArray() + secretBytes), date)
            val regionKey = hmac(dateKey, config.region)
            val serviceKey = hmac(regionKey, "s3")
            val signingKey = hmac(serviceKey, "aws4_request")
            val signature = hmac(signingKey, stringToSign).toHex()
            Request.Builder()
                .url(url)
                .header("x-amz-date", timestamp)
                .header("x-amz-content-sha256", payloadHash)
                .header(
                    "Authorization",
                    "AWS4-HMAC-SHA256 Credential=${config.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
                )
                .header("User-Agent", "Fylz/1")
        } finally {
            secretBytes.fill(0)
            secretAccessKey.fill('\u0000')
        }
    }

    private fun objectUrl(config: S3CompatibleConfig, key: String?): HttpUrl {
        validate(config)
        val endpoint = config.endpoint.trimEnd('/').toHttpUrl()
        val builder = if (config.pathStyle) endpoint.newBuilder().addPathSegment(config.bucket) else {
            endpoint.newBuilder().host("${config.bucket}.${endpoint.host}")
        }
        key?.split('/')?.forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun canonicalUri(url: HttpUrl): String = url.encodedPath.ifEmpty { "/" }

    private fun canonicalQuery(url: HttpUrl): String = buildList {
        for (index in 0 until url.querySize) {
            add(url.queryParameterName(index) to (url.queryParameterValue(index) ?: ""))
        }
    }.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString("&") { "${awsEncode(it.first)}=${awsEncode(it.second)}" }

    private fun parseList(xml: String): ObjectListPage {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }
        val entries = mutableListOf<ObjectEntry>()
        var event = parser.eventType
        var inContents = false
        var inPrefix = false
        var key: String? = null
        var size: Long? = null
        var modified: String? = null
        var etag: String? = null
        var nextToken: String? = null
        var truncated = false
        while (event != XmlPullParser.END_DOCUMENT && entries.size < MAX_LIST_RESULTS * 2) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Contents" -> { inContents = true; key = null; size = null; modified = null; etag = null }
                    "CommonPrefixes" -> { inPrefix = true; key = null }
                    "Key" -> if (inContents) key = parser.nextText()
                    "Prefix" -> if (inPrefix) key = parser.nextText()
                    "Size" -> if (inContents) size = parser.nextText().toLongOrNull()
                    "LastModified" -> if (inContents) modified = parser.nextText()
                    "ETag" -> if (inContents) etag = parser.nextText().trim('"')
                    "NextContinuationToken" -> nextToken = parser.nextText()
                    "IsTruncated" -> truncated = parser.nextText().equals("true", true)
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "Contents" -> {
                        key?.let { entries += ObjectEntry(it, size, modified, etag) }
                        inContents = false
                    }
                    "CommonPrefixes" -> {
                        key?.let { entries += ObjectEntry(it, null, null, null, directoryPrefix = true) }
                        inPrefix = false
                    }
                }
            }
            event = parser.next()
        }
        return ObjectListPage(entries, nextToken, truncated)
    }

    private fun validate(config: S3CompatibleConfig) {
        val uri = URI(config.endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Object storage endpoints must use HTTPS." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "The object storage endpoint cannot contain credentials, query parameters or fragments."
        }
    }

    private fun validateKey(key: String) {
        require(key.isNotEmpty() && key.length <= MAX_KEY_CHARS)
        require(!key.startsWith('/') && '\u0000' !in key)
        require(key.split('/').none { it == "." || it == ".." })
    }

    private fun portSuffix(url: HttpUrl): String = when {
        url.scheme == "https" && url.port == 443 -> ""
        url.scheme == "http" && url.port == 80 -> ""
        else -> ":${url.port}"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHex()
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray(Charsets.UTF_8))
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun awsEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20").replace("%7E", "~")
    private fun safeError(value: String): String = value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(300)

    private companion object {
        val DATE = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val TIMESTAMP = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val EMPTY_SHA256 = sha256(ByteArray(0))
        const val MAX_KEY_CHARS = 1_024
        const val MAX_LIST_RESULTS = 1_000
        const val MAX_XML_CHARS = 4_000_000
    }
}
