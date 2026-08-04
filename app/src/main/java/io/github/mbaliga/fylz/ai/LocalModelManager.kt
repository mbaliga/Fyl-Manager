package io.github.mbaliga.fylz.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class LocalModelDescriptor(
    val id: String,
    val displayName: String,
    val downloadUrl: String,
    val sha256: String,
    val expectedBytes: Long? = null,
    val licenseName: String,
    val licenseUrl: String,
)

/** User-triggered, checksum-verified downloadable model storage. No silent downloads or updates. */
class LocalModelManager(context: Context) {
    private val modelDirectory = File(context.filesDir, "models").apply { mkdirs() }

    data class DownloadProgress(val completedBytes: Long, val totalBytes: Long?)

    fun installed(descriptor: LocalModelDescriptor): Boolean =
        modelFile(descriptor).takeIf(File::isFile)?.let { sha256(it) == descriptor.sha256.lowercase() } == true

    fun installedModels(): List<File> = modelDirectory.listFiles().orEmpty().filter(File::isFile)

    suspend fun download(
        descriptor: LocalModelDescriptor,
        userConfirmedLicenseAndDownload: Boolean,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(userConfirmedLicenseAndDownload) {
            "Model download requires explicit confirmation of the download and license."
        }
        require(descriptor.id.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) { "Invalid model id." }
        require(descriptor.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid model checksum." }
        val uri = URI(descriptor.downloadUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Model downloads must use HTTPS."
        }

        val finalFile = modelFile(descriptor)
        if (finalFile.isFile && sha256(finalFile) == descriptor.sha256.lowercase()) return@withContext finalFile
        val partial = File(modelDirectory, ".${descriptor.id}.${System.nanoTime()}.part")
        val connection = URL(descriptor.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.requestMethod = "GET"
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Model download failed with HTTP ${connection.responseCode}."
            }
            val reportedLength = connection.contentLengthLong.takeIf { it >= 0L }
            descriptor.expectedBytes?.let { expected ->
                if (reportedLength != null) {
                    require(reportedLength == expected) { "Model download size does not match its manifest." }
                }
            }

            var completed = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        completed += count
                        onProgress(DownloadProgress(completed, descriptor.expectedBytes ?: reportedLength))
                    }
                    output.fd.sync()
                }
            }
            descriptor.expectedBytes?.let { expected ->
                require(partial.length() == expected) { "Downloaded model is incomplete." }
            }
            require(sha256(partial) == descriptor.sha256.lowercase()) {
                "Downloaded model checksum verification failed."
            }
            check(partial.renameTo(finalFile)) { "Unable to install the verified model." }
            finalFile
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    fun remove(descriptor: LocalModelDescriptor, confirmed: Boolean) {
        require(confirmed) { "Removing a model requires confirmation." }
        val file = modelFile(descriptor)
        check(!file.exists() || file.delete()) { "Unable to remove the local model." }
    }

    private fun modelFile(descriptor: LocalModelDescriptor): File =
        File(modelDirectory, "${descriptor.id}.model")

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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
