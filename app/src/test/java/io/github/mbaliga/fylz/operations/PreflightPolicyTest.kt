package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.storage.VolumeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1.5: [PreflightPolicy.evaluate] against every rule the brief names, and [PreflightPolicy.sanitizedName].
 * [PreflightItem] and [VolumeInfo] are both ordinary data classes and [PreflightPolicy] itself
 * touches nothing Android -- Robolectric is only needed here for `Uri.parse` in this test's own
 * [item] fixture helper, the same reason [TransferBenchmarkTest] needs it for an otherwise-pure
 * class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreflightPolicyTest {

    private fun item(
        name: String,
        isDirectory: Boolean = false,
        totalBytes: Long? = 1_000L,
    ): PreflightItem = PreflightItem(
        sourceUri = Uri.parse("content://preflight-test/${name.hashCode()}"),
        name = name,
        isDirectory = isDirectory,
        totalBytes = totalBytes,
    )

    private fun volume(
        filesystemType: String? = "ext4",
        freeBytes: Long? = Long.MAX_VALUE / 2,
        caseInsensitive: Boolean = false,
    ): VolumeInfo = VolumeInfo(filesystemType, freeBytes, caseInsensitive)

    // --- illegal characters / trailing space or dot -----------------------------------------

    @Test
    fun `illegal characters are flagged on vfat and exfat but not on ext4`() {
        val withColon = item("bad:name.txt")

        val onVfat = PreflightPolicy.evaluate(listOf(withColon), volume(filesystemType = "vfat"))
        val onExfat = PreflightPolicy.evaluate(listOf(withColon), volume(filesystemType = "exfat"))
        val onExt4 = PreflightPolicy.evaluate(listOf(withColon), volume(filesystemType = "ext4"))

        assertTrue(onVfat.problems.single() is PreflightProblem.IllegalCharacters)
        assertTrue(onExfat.problems.single() is PreflightProblem.IllegalCharacters)
        assertTrue("ext4 has no illegal-character rule", onExt4.problems.isEmpty())
    }

    @Test
    fun `every FAT-illegal character is reported, not just the first`() {
        val name = "a\\b/c:d*e?f\"g<h>i|j.txt"

        val result = PreflightPolicy.evaluate(listOf(item(name)), volume(filesystemType = "vfat"))

        val problem = result.problems.single() as PreflightProblem.IllegalCharacters
        assertEquals(setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|'), problem.characters)
    }

    @Test
    fun `a trailing space or dot is flagged on vfat and exfat only`() {
        val trailingSpace = item("name ")
        val trailingDot = item("name.")
        val clean = item("name")

        val result = PreflightPolicy.evaluate(listOf(trailingSpace, trailingDot, clean), volume(filesystemType = "exfat"))

        assertEquals(2, result.problems.count { it is PreflightProblem.TrailingSpaceOrDot })
        assertTrue(result.problems.none { it.item == clean })
    }

    @Test
    fun `sanitizedName replaces illegal characters and a trailing space or dot with underscore`() {
        assertEquals("a_b_c.txt", PreflightPolicy.sanitizedName("a:b/c.txt"))
        assertEquals("name_", PreflightPolicy.sanitizedName("name "))
        assertEquals("name_", PreflightPolicy.sanitizedName("name."))
        assertEquals("clean.txt", PreflightPolicy.sanitizedName("clean.txt"))
    }

    // --- name length --------------------------------------------------------------------------

    @Test
    fun `a name over 255 UTF-8 bytes is flagged regardless of filesystem`() {
        val longName = "a".repeat(300) + ".txt"

        val result = PreflightPolicy.evaluate(listOf(item(longName)), volume(filesystemType = "ext4"))

        val problem = result.problems.single() as PreflightProblem.NameTooLong
        assertEquals(255, problem.limitBytes)
    }

    @Test
    fun `a 255-byte name is not flagged, a 256-byte name is`() {
        val exactly255 = item("a".repeat(255))
        val exactly256 = item("a".repeat(256))

        val result = PreflightPolicy.evaluate(listOf(exactly255, exactly256), volume())

        assertEquals(1, result.problems.size)
        assertEquals(exactly256, (result.problems.single() as PreflightProblem.NameTooLong).item)
    }

    @Test
    fun `name length is measured in UTF-8 bytes, not characters`() {
        // Each of these is one Kotlin Char but 3 UTF-8 bytes -- 100 of them is 300 bytes, over
        // the limit, even though the string itself is only 100 characters long.
        val name = "あ".repeat(100)

        val result = PreflightPolicy.evaluate(listOf(item(name)), volume())

        assertTrue(result.problems.single() is PreflightProblem.NameTooLong)
    }

    // --- vfat's 4 GiB file limit --------------------------------------------------------------

    @Test
    fun `a file at exactly 4 GiB minus 1 fits on vfat, one byte more does not`() {
        val fits = item("big.bin", totalBytes = PreflightPolicy.VFAT_MAX_FILE_BYTES)
        val tooBig = item("toobig.bin", totalBytes = PreflightPolicy.VFAT_MAX_FILE_BYTES + 1)

        val result = PreflightPolicy.evaluate(listOf(fits, tooBig), volume(filesystemType = "vfat"))

        assertEquals(1, result.problems.size)
        assertEquals(tooBig, (result.problems.single() as PreflightProblem.FileTooLargeForVfat).item)
    }

    @Test
    fun `the vfat file-size limit does not apply to exfat or a directory`() {
        val bigFileOnExfat = item("big.bin", totalBytes = PreflightPolicy.VFAT_MAX_FILE_BYTES + 1)
        val bigFolderOnVfat = item("folder", isDirectory = true, totalBytes = PreflightPolicy.VFAT_MAX_FILE_BYTES + 1)

        val onExfat = PreflightPolicy.evaluate(listOf(bigFileOnExfat), volume(filesystemType = "exfat"))
        val onVfatDirectory = PreflightPolicy.evaluate(listOf(bigFolderOnVfat), volume(filesystemType = "vfat"))

        assertTrue("exfat has no 4 GiB single-file limit", onExfat.problems.none { it is PreflightProblem.FileTooLargeForVfat })
        assertTrue("a directory has no single file size to exceed", onVfatDirectory.problems.none { it is PreflightProblem.FileTooLargeForVfat })
    }

    // --- case-insensitive collisions -----------------------------------------------------------

    @Test
    fun `case-insensitive collisions are flagged on a case-insensitive volume`() {
        val first = item("Photo.jpg")
        val second = item("photo.JPG")
        val third = item("PHOTO.jpg")

        val result = PreflightPolicy.evaluate(listOf(first, second, third), volume(caseInsensitive = true))

        val collisions = result.problems.filterIsInstance<PreflightProblem.NameCollision>()
        assertEquals(2, collisions.size)
        assertEquals(setOf(second, third), collisions.map { it.item }.toSet())
        assertTrue(collisions.all { it.collidesWithName == "Photo.jpg" })
    }

    @Test
    fun `case-insensitive collisions are also flagged on the FAT family even if caseInsensitive is false`() {
        val result = PreflightPolicy.evaluate(
            listOf(item("a.txt"), item("A.txt")),
            volume(filesystemType = "vfat", caseInsensitive = false),
        )

        assertEquals(1, result.problems.count { it is PreflightProblem.NameCollision })
    }

    @Test
    fun `distinct names never collide, even on a case-insensitive volume`() {
        val result = PreflightPolicy.evaluate(
            listOf(item("a.txt"), item("b.txt")),
            volume(caseInsensitive = true),
        )

        assertTrue(result.problems.none { it is PreflightProblem.NameCollision })
    }

    @Test
    fun `case differences never collide on a case-sensitive volume`() {
        val result = PreflightPolicy.evaluate(
            listOf(item("a.txt"), item("A.txt")),
            volume(filesystemType = "ext4", caseInsensitive = false),
        )

        assertTrue(result.problems.isEmpty())
    }

    // --- free space ----------------------------------------------------------------------------

    @Test
    fun `insufficient space is flagged when free bytes fall under required plus a 5 percent margin`() {
        val items = listOf(item("a.bin", totalBytes = 1_000L), item("b.bin", totalBytes = 1_000L))
        // required = 2000, margin = 100, requiredWithMargin = 2100
        val result = PreflightPolicy.evaluate(items, volume(freeBytes = 2_050L))

        val insufficient = requireNotNull(result.insufficientSpace)
        assertEquals(2_100L, insufficient.requiredBytes)
        assertEquals(2_050L, insufficient.availableBytes)
    }

    @Test
    fun `exactly enough space including the margin is not flagged`() {
        val items = listOf(item("a.bin", totalBytes = 1_000L))
        // required = 1000, margin = 50, requiredWithMargin = 1050
        val result = PreflightPolicy.evaluate(items, volume(freeBytes = 1_050L))

        assertNull(result.insufficientSpace)
    }

    @Test
    fun `unknown free space is never flagged as insufficient`() {
        val items = listOf(item("a.bin", totalBytes = Long.MAX_VALUE))

        val result = PreflightPolicy.evaluate(items, volume(freeBytes = null))

        assertNull(result.insufficientSpace)
    }

    @Test
    fun `an item with an unknown size is excluded from the required total rather than failing the check`() {
        val items = listOf(item("known.bin", totalBytes = 1_000L), item("unknown.bin", totalBytes = null))
        // required counts only the known item: 1000, margin 50, requiredWithMargin 1050
        val result = PreflightPolicy.evaluate(items, volume(freeBytes = 1_040L))

        val insufficient = requireNotNull(result.insufficientSpace)
        assertEquals(1_050L, insufficient.requiredBytes)
    }

    // --- overall result shape -------------------------------------------------------------------

    @Test
    fun `isClean is true only when there are no problems and no insufficient-space flag`() {
        val clean = PreflightPolicy.evaluate(listOf(item("ok.txt")), volume(freeBytes = Long.MAX_VALUE / 2))
        assertTrue(clean.isClean)

        val dirty = PreflightPolicy.evaluate(listOf(item("bad:name")), volume(filesystemType = "vfat"))
        assertTrue(!dirty.isClean)
    }

    @Test
    fun `an empty selection has no problems and no space requirement`() {
        val result = PreflightPolicy.evaluate(emptyList(), volume(filesystemType = "vfat", freeBytes = 0L))
        assertTrue(result.isClean)
    }
}
