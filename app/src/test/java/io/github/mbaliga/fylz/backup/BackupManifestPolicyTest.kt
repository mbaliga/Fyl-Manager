package io.github.mbaliga.fylz.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestPolicyTest {
    @Test
    fun `allows a valid nested backup manifest`() {
        val result = BackupManifestPolicy.validate(
            manifest(
                directory("Photos"),
                directory("Photos/Trip"),
                file("Photos/Trip/image.jpg"),
                file("notes.txt"),
            ),
        )

        assertTrue(result.reason, result.allowed)
    }

    @Test
    fun `rejects traversal absolute and backslash paths`() {
        assertFalse(BackupManifestPolicy.validate(manifest(file("../secret.txt"))).allowed)
        assertFalse(BackupManifestPolicy.validate(manifest(file("/absolute.txt"))).allowed)
        assertFalse(BackupManifestPolicy.validate(manifest(file("folder\\file.txt"))).allowed)
    }

    @Test
    fun `rejects duplicate and case colliding paths`() {
        assertFalse(
            BackupManifestPolicy.validate(
                manifest(file("Photos/image.jpg"), file("photos/IMAGE.jpg")),
            ).allowed,
        )
    }

    @Test
    fun `rejects file ancestor conflicts`() {
        assertFalse(
            BackupManifestPolicy.validate(
                manifest(file("assets"), file("assets/icon.svg")),
            ).allowed,
        )
    }

    @Test
    fun `rejects malformed file and directory metadata`() {
        assertFalse(
            BackupManifestPolicy.validate(
                manifest(
                    BackupManifestEntry("folder", true, null, 4, null, null),
                ),
            ).allowed,
        )
        assertFalse(
            BackupManifestPolicy.validate(
                manifest(
                    BackupManifestEntry("file.txt", false, "text/plain", 4, "bad-hash", null),
                ),
            ).allowed,
        )
    }

    @Test
    fun `rejects reserved manifest path and unsafe source names`() {
        assertFalse(
            BackupManifestPolicy.validate(
                manifest(file(BackupManifestPolicy.MANIFEST_FILE)),
            ).allowed,
        )
        assertFalse(BackupManifestPolicy.validateSourceSegment("folder/name").allowed)
        assertFalse(BackupManifestPolicy.validateSourceSegment("folder\\name").allowed)
        assertFalse(BackupManifestPolicy.validateSourceSegment("..").allowed)
        assertFalse(BackupManifestPolicy.validateSourceSegment("bad\u0000name").allowed)
    }

    private fun manifest(vararg entries: BackupManifestEntry) = BackupManifest(
        backupId = "backup-id",
        planId = "plan-id",
        sourceTreeUri = "content://provider/tree/source",
        sourceDisplayName = "Source",
        createdAtMillis = 1L,
        entries = entries.toList(),
    )

    private fun directory(path: String) = BackupManifestEntry(
        relativePath = path,
        directory = true,
        mimeType = null,
        sizeBytes = 0L,
        sha256 = null,
        lastModifiedMillis = null,
    )

    private fun file(path: String) = BackupManifestEntry(
        relativePath = path,
        directory = false,
        mimeType = "application/octet-stream",
        sizeBytes = 4L,
        sha256 = "0".repeat(64),
        lastModifiedMillis = null,
    )
}
