package io.github.mbaliga.fylz.operations

import android.os.CancellationSignal
import android.os.FileUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1.3: debug-only diagnostics for the three scenarios the brief names, timing the exact
 * [android.os.FileUtils.copy] call [LocalFileTransfer] uses in production -- not the full
 * `DocumentsProvider`/[DocNode] staging pipeline on top of it, since that plumbing's overhead is
 * orthogonal to what "at least 90% of `cp` throughput" is actually asking about, and going through
 * it here would mean resolving a real device's [io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider]
 * root for [primaryVolumeDir]/[secondaryVolumeDir], for no benefit to the number this produces.
 *
 * Manually invoked, not an automated pass/fail check: there is no device or emulator in this
 * sandbox to measure real throughput against, and a synthetic 4 GB write here would only prove
 * this sandbox's own disk is fast or slow, not this codebase's copy path. [run]'s own correctness
 * -- each scenario copies the right number of bytes, timed start to finish -- is what
 * [TransferBenchmarkTest] proves, at sizes that finish in milliseconds; the actual throughput
 * comparison against `cp` is `docs/agent/DEVICE_CHECKS.md`'s.
 */
object TransferBenchmark {

    data class Scenario(val name: String, val bytes: Long, val elapsedMillis: Long) {
        val megabytesPerSecond: Double
            get() = if (elapsedMillis <= 0) 0.0 else (bytes / 1_048_576.0) / (elapsedMillis / 1000.0)
    }

    data class Report(val scenarios: List<Scenario>) {
        /** One line per scenario, meant for the progress log the brief asks for. */
        fun summary(): String = scenarios.joinToString("\n") { s ->
            "${s.name}: ${s.bytes} bytes in ${s.elapsedMillis} ms (${"%.1f".format(s.megabytesPerSecond)} MB/s)"
        }
    }

    /**
     * @param primaryVolumeDir a writable directory on the volume to benchmark same-volume copies
     *   against.
     * @param secondaryVolumeDir a writable directory on a *different* [android.os.storage.StorageVolume]
     *   (an SD card or USB volume) for the cross-volume scenario; that scenario is skipped when
     *   null, since not every device has a second volume to test against.
     * @param largeFileBytes / [manySmallFilesCount] / [smallFileBytes] default to the brief's own
     *   4 GB and 10,000×4 KB targets; overridden in tests to sizes that finish quickly.
     */
    suspend fun run(
        primaryVolumeDir: File,
        secondaryVolumeDir: File? = null,
        largeFileBytes: Long = 4L * 1024 * 1024 * 1024,
        manySmallFilesCount: Int = 10_000,
        smallFileBytes: Int = 4 * 1024,
    ): Report = withContext(Dispatchers.IO) {
        val scenarios = mutableListOf<Scenario>()
        scenarios += largeFileCopy("4 GB copy, same volume", primaryVolumeDir, primaryVolumeDir, largeFileBytes)
        if (secondaryVolumeDir != null) {
            scenarios += largeFileCopy(
                "4 GB copy, internal storage to SD/USB",
                primaryVolumeDir,
                secondaryVolumeDir,
                largeFileBytes,
            )
        }
        scenarios += manySmallFiles(primaryVolumeDir, manySmallFilesCount, smallFileBytes)
        Report(scenarios)
    }

    private fun largeFileCopy(name: String, sourceDir: File, targetDir: File, bytes: Long): Scenario {
        val source = File(sourceDir, "fylz-benchmark-source.bin")
        val target = File(targetDir, "fylz-benchmark-target.bin")
        try {
            fillRandom(source, bytes)
            val elapsed = timeMillisOf { copyOnce(source, target) }
            return Scenario(name, bytes, elapsed)
        } finally {
            source.delete()
            target.delete()
        }
    }

    private fun manySmallFiles(dir: File, count: Int, sizeBytes: Int): Scenario {
        val sourceDir = File(dir, "fylz-benchmark-many-source")
        val targetDir = File(dir, "fylz-benchmark-many-target")
        try {
            check(sourceDir.mkdirs() && targetDir.mkdirs()) { "Unable to prepare benchmark directories" }
            val random = Random(0)
            repeat(count) { i -> File(sourceDir, "f$i.bin").writeBytes(random.nextBytes(sizeBytes)) }
            var totalBytes = 0L
            val elapsed = timeMillisOf {
                sourceDir.listFiles().orEmpty().forEach { file ->
                    copyOnce(file, File(targetDir, file.name))
                    totalBytes += file.length()
                }
            }
            return Scenario("$count files of $sizeBytes bytes each", totalBytes, elapsed)
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    private fun copyOnce(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                FileUtils.copy(
                    input.fd,
                    output.fd,
                    null as CancellationSignal?,
                    Executor { it.run() },
                    FileUtils.ProgressListener { },
                )
                output.fd.sync()
            }
        }
    }

    private fun fillRandom(file: File, bytes: Long) {
        val random = Random(0)
        val buffer = ByteArray(minOf(bytes, (1024L * 1024)).toInt().coerceAtLeast(1))
        file.outputStream().use { out ->
            var remaining = bytes
            while (remaining > 0) {
                val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                random.nextBytes(buffer, 0, chunk)
                out.write(buffer, 0, chunk)
                remaining -= chunk
            }
        }
    }

    private inline fun timeMillisOf(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }
}
