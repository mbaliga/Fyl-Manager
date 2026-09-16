package io.github.mbaliga.fylz.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemSnapshotTest {

    @Test
    fun `metadata defaults empty rather than requiring every caller to pass it`() {
        val snapshot = ItemSnapshot(
            ref = ItemRef("file", "t", "t"),
            parentRef = null,
            displayName = "root",
            kind = EntryKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtMillis = null,
            contentType = null,
            versionStamp = null,
            capabilities = emptySet(),
        )
        assertTrue(snapshot.metadata.isEmpty())
    }

    @Test
    fun `a null size or timestamp is preserved, not coerced to zero`() {
        val snapshot = ItemSnapshot(
            ref = ItemRef("file", "t", "t:dir"),
            parentRef = ItemRef("file", "t", "t"),
            displayName = "a folder",
            kind = EntryKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtMillis = null,
            contentType = null,
            versionStamp = null,
            capabilities = setOf(ItemCapability.LIST),
        )
        assertEquals(null, snapshot.sizeBytes)
        assertEquals(null, snapshot.modifiedAtMillis)
    }
}
