package io.github.mbaliga.fylz.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1.5: the pure parts of [VolumeInfoResolver] -- parsing `/proc/self/mounts`-shaped text and
 * matching a real path against it, and the shared-storage/case-insensitivity check -- plus one
 * integration test proving [VolumeInfoResolver.resolve] itself is actually wired to a real
 * `StatFs` call. The `/proc/self/mounts` read specifically ([VolumeInfoResolver.mountFilesystemType])
 * is still trusted untested the same way [io.github.mbaliga.fylz.data.ArchiveService]'s own
 * `StatFs`/root-query calls are: this sandbox's own `/proc/self/mounts` is real but not a fixture
 * this test controls, so its filesystem type is not something a test can assert a fixed value for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumeInfoTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    // --- parseMounts -----------------------------------------------------------------------

    @Test
    fun `parseMounts reads device, mount point and filesystem type from a real-shaped line`() {
        val mounts = "/dev/block/dm-1 / ext4 ro,seclabel 0 0\n" +
            "/dev/block/vold/public:179,1 /storage/1234-5678 vfat rw,nosuid 0 0"

        val entries = parseMounts(mounts)

        assertEquals(
            listOf(MountEntry("/", "ext4"), MountEntry("/storage/1234-5678", "vfat")),
            entries,
        )
    }

    @Test
    fun `parseMounts unescapes an octal-escaped space in a mount point`() {
        val mounts = "/dev/sda1 /storage/My\\040Card exfat rw 0 0"

        val entries = parseMounts(mounts)

        assertEquals("/storage/My Card", entries.single().mountPoint)
    }

    @Test
    fun `parseMounts skips a malformed line rather than failing the whole parse`() {
        val mounts = "too short\n/dev/sda1 /storage/card exfat rw 0 0"

        val entries = parseMounts(mounts)

        assertEquals(listOf(MountEntry("/storage/card", "exfat")), entries)
    }

    @Test
    fun `parseMounts returns nothing for blank input`() {
        assertEquals(emptyList<MountEntry>(), parseMounts(""))
    }

    // --- filesystemTypeForPath ----------------------------------------------------------------

    private val sampleMounts = "rootfs / ext4 ro 0 0\n" +
        "/dev/fuse /storage/emulated fuse rw 0 0\n" +
        "/dev/block/vold/public:179,1 /storage/1234-5678 vfat rw 0 0"

    @Test
    fun `filesystemTypeForPath picks the longest matching mount point, not just the root mount`() {
        assertEquals("fuse", filesystemTypeForPath("/storage/emulated/0/Download", sampleMounts))
        assertEquals("vfat", filesystemTypeForPath("/storage/1234-5678/DCIM/photo.jpg", sampleMounts))
        assertEquals("ext4", filesystemTypeForPath("/data/user/0/some.app", sampleMounts))
    }

    @Test
    fun `filesystemTypeForPath matches a path equal to the mount point itself`() {
        assertEquals("vfat", filesystemTypeForPath("/storage/1234-5678", sampleMounts))
    }

    @Test
    fun `filesystemTypeForPath does not match a sibling path that merely shares a prefix string`() {
        // /storage/1234-5678-other is NOT under /storage/1234-5678 -- must not match on a bare
        // string prefix without the path separator boundary, and falls back to the root mount.
        assertEquals("ext4", filesystemTypeForPath("/storage/1234-5678-other/file", sampleMounts))
    }

    @Test
    fun `filesystemTypeForPath returns null when nothing in the mounts text contains the path`() {
        assertNull(filesystemTypeForPath("/some/path", "/dev/sda1 /storage/card exfat rw 0 0"))
    }

    // --- isUnderSharedStorage -------------------------------------------------------------------

    @Test
    fun `isUnderSharedStorage is true for a path under storage emulated`() {
        assertTrue(VolumeInfoResolver.isUnderSharedStorage(File("/storage/emulated/0/DCIM")))
        assertTrue(VolumeInfoResolver.isUnderSharedStorage(File("/storage/emulated")))
    }

    @Test
    fun `isUnderSharedStorage is false for a path outside storage emulated`() {
        assertTrue(!VolumeInfoResolver.isUnderSharedStorage(File("/storage/1234-5678/DCIM")))
        assertTrue(!VolumeInfoResolver.isUnderSharedStorage(File("/data/user/0/some.app")))
    }

    // --- resolve (integration) -------------------------------------------------------------------

    @Test
    fun `resolve reads a real free-byte count via StatFs when given a real path`() {
        val destination = tempFolder.newFolder("destination")

        val volume = VolumeInfoResolver.resolve(
            RuntimeEnvironment.getApplication(),
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID),
            destination,
        )

        assertTrue("expected a real StatFs reading for an existing directory", (volume.freeBytes ?: -1L) >= 0L)
    }

    @Test
    fun `resolve falls back to null filesystem type and a root-query for a path this app can't resolve`() {
        val volume = VolumeInfoResolver.resolve(
            RuntimeEnvironment.getApplication(),
            android.net.Uri.parse("content://com.example.cloud.documents/tree/root"),
            null,
        )

        assertNull("no real path means no way to read a mount's filesystem type", volume.filesystemType)
    }
}
