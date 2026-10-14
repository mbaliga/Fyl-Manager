package io.github.mbaliga.fylz.operations

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1.3: [TransferBenchmark]'s own correctness at sizes that finish in milliseconds -- each
 * scenario copies the right number of bytes and cleans up after itself. Real throughput against
 * `cp` needs a device; see `docs/agent/DEVICE_CHECKS.md`. Robolectric (not a plain JVM test) is
 * required here specifically for [android.os.FileUtils.copy]'s real shadow -- the default unit
 * test stub throws "not mocked" for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferBenchmarkTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `runs the same-volume and many-small-files scenarios and cleans up its own fixtures`() = runBlocking {
        val primary = tempFolder.newFolder("primary")

        val report = TransferBenchmark.run(
            primaryVolumeDir = primary,
            largeFileBytes = 10_000,
            manySmallFilesCount = 5,
            smallFileBytes = 100,
        )

        assertEquals(2, report.scenarios.size)
        val largeFile = report.scenarios[0]
        assertEquals(10_000L, largeFile.bytes)
        assertTrue(largeFile.elapsedMillis >= 0)
        val manyFiles = report.scenarios[1]
        assertEquals(500L, manyFiles.bytes) // 5 files * 100 bytes
        assertTrue(report.summary().contains("5 files of 100 bytes each"))

        assertEquals(
            "no benchmark fixtures should survive a run",
            emptyList<String>(),
            primary.listFiles()?.map { it.name } ?: emptyList<String>(),
        )
    }

    @Test
    fun `includes a second scenario when a secondary volume directory is given`() = runBlocking {
        val primary = tempFolder.newFolder("primary")
        val secondary = tempFolder.newFolder("secondary")

        val report = TransferBenchmark.run(
            primaryVolumeDir = primary,
            secondaryVolumeDir = secondary,
            largeFileBytes = 1_000,
            manySmallFilesCount = 1,
            smallFileBytes = 10,
        )

        assertEquals(3, report.scenarios.size)
        assertTrue(report.scenarios[1].name.contains("SD/USB"))
        assertFalse("the secondary volume's own fixture must be cleaned up too", File(secondary, "fylz-benchmark-target.bin").exists())
    }
}
