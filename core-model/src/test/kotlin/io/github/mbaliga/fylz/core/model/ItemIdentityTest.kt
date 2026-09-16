package io.github.mbaliga.fylz.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemIdentityTest {

    private fun ref(item: String, location: String = "tree-1") = ItemRef("file", location, item)

    @Test
    fun `same item requires every ref field to match`() {
        assertTrue(ItemIdentity.sameItem(ref("a"), ref("a")))
        assertFalse(ItemIdentity.sameItem(ref("a"), ref("b")))
        assertFalse(ItemIdentity.sameItem(ref("a", "tree-1"), ref("a", "tree-2")))
        assertFalse(ItemIdentity.sameItem(ItemRef("file", "t", "a"), ItemRef("saf", "t", "a")))
    }

    @Test
    fun `a display name never enters the comparison`() {
        // ItemRef carries no display name at all -- there is nothing to accidentally compare.
        // This test exists so a future field addition to ItemRef gets asked the question.
        val a = ref("same-id")
        val b = ref("same-id")
        assertTrue(ItemIdentity.sameItem(a, b))
    }

    @Test
    fun `isRoot is true exactly when opaqueItemId echoes locationId`() {
        assertTrue(ItemIdentity.isRoot(ItemRef("file", "tree-1", "tree-1")))
        assertFalse(ItemIdentity.isRoot(ItemRef("file", "tree-1", "tree-1:Sub")))
    }

    // ── compareVersions ────────────────────────────────────────────────────────────────

    private fun composite(size: Long? = 100L, modified: Long? = 1_000L) =
        VersionStamp.Composite(size, modified)

    @Test
    fun `identical composite stamps compare same`() {
        assertEquals(
            ItemIdentity.VersionComparison.Same,
            ItemIdentity.compareVersions(composite(), composite()),
        )
    }

    @Test
    fun `a differing size or timestamp compares changed`() {
        assertEquals(
            ItemIdentity.VersionComparison.Changed,
            ItemIdentity.compareVersions(composite(size = 100L), composite(size = 200L)),
        )
        assertEquals(
            ItemIdentity.VersionComparison.Changed,
            ItemIdentity.compareVersions(composite(modified = 1_000L), composite(modified = 2_000L)),
        )
    }

    @Test
    fun `a null stamp on either side is unknown not same`() {
        assertEquals(ItemIdentity.VersionComparison.Unknown, ItemIdentity.compareVersions(null, composite()))
        assertEquals(ItemIdentity.VersionComparison.Unknown, ItemIdentity.compareVersions(composite(), null))
        assertEquals(ItemIdentity.VersionComparison.Unknown, ItemIdentity.compareVersions(null, null))
    }

    @Test
    fun `a partially-missing composite field is unknown not same`() {
        assertEquals(
            "a provider that reports no size on either side must not be read as a size match",
            ItemIdentity.VersionComparison.Unknown,
            ItemIdentity.compareVersions(composite(size = null), composite(size = null)),
        )
        assertEquals(
            ItemIdentity.VersionComparison.Unknown,
            ItemIdentity.compareVersions(composite(modified = null), composite()),
        )
    }

    @Test
    fun `identical revision tokens compare same, differing tokens compare changed`() {
        assertEquals(
            ItemIdentity.VersionComparison.Same,
            ItemIdentity.compareVersions(VersionStamp.Revision("v1"), VersionStamp.Revision("v1")),
        )
        assertEquals(
            ItemIdentity.VersionComparison.Changed,
            ItemIdentity.compareVersions(VersionStamp.Revision("v1"), VersionStamp.Revision("v2")),
        )
    }

    @Test
    fun `comparing across stamp shapes is unknown, not a detected change`() {
        assertEquals(
            ItemIdentity.VersionComparison.Unknown,
            ItemIdentity.compareVersions(composite(), VersionStamp.Revision("v1")),
        )
    }
}
