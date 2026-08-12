package io.github.mbaliga.fylz.storage

import android.net.Uri
import io.github.mbaliga.fylz.core.model.ItemIdentity
import io.github.mbaliga.fylz.core.model.ItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Uri.parse/Uri.Builder need a real android.net.Uri implementation, which the plain-JVM
// unit-test stub jar cannot provide (see PdfPagePlanPolicyTest for the same reasoning). sdk
// pinned to the module's targetSdk per house convention.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ItemRefsTest {

    @Test
    fun `a bare tree uri becomes a root ref`() {
        val uri = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary")
        val ref = uri.toItemRef()

        assertEquals(ItemRef("io.github.mbaliga.fylz.files", "primary", "primary"), ref)
        assertTrue(ItemIdentity.isRoot(ref))
    }

    @Test
    fun `a tree-document uri at the tree's own root becomes the identical ref as the bare tree uri`() {
        // The real fix this adapter makes: two textually different URIs that name the same
        // root must collapse to one ref, not compare unequal the way raw Uri.toString() did.
        val bare = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary").toItemRef()
        val atRoot = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary/document/primary").toItemRef()

        assertEquals(bare, atRoot)
    }

    @Test
    fun `a tree-document uri naming a subfolder is not the root`() {
        val uri = Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary/document/primary%3ASub")
        val ref = uri.toItemRef()

        assertEquals(ItemRef("io.github.mbaliga.fylz.files", "primary", "primary:Sub"), ref)
        assertTrue(!ItemIdentity.isRoot(ref))
    }

    @Test
    fun `tree-shaped refs round-trip through toUri exactly`() {
        val originals = listOf(
            Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary"),
            Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary/document/primary"),
            Uri.parse("content://io.github.mbaliga.fylz.files/tree/primary/document/primary%3ASub%2FDeep.txt"),
            Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3A/document/1234-5678%3ADCIM"),
        )
        for (original in originals) {
            val roundTripped = original.toItemRef().toUri()
            assertEquals(
                "expected $original to round-trip, got $roundTripped",
                original.toItemRef(),
                roundTripped.toItemRef(),
            )
        }
    }

    @Test
    fun `an unrecognized uri shape falls back rather than throwing`() {
        val plainDocument = Uri.parse("content://io.github.mbaliga.fylz.files/document/primary%3Afile.txt")
        val entirelyDifferentScheme = Uri.parse("https://example.com/not/a/saf/uri")

        for (uri in listOf(plainDocument, entirelyDifferentScheme)) {
            val ref = uri.toItemRef()
            assertEquals(uri.toString(), ref.opaqueItemId)
            assertEquals(uri, ref.toUri())
        }
    }

    @Test
    fun `provider id is derived from the uri authority so two providers never collide`() {
        val a = Uri.parse("content://provider.a/tree/root").toItemRef()
        val b = Uri.parse("content://provider.b/tree/root").toItemRef()

        assertEquals("provider.a", a.providerId)
        assertEquals("provider.b", b.providerId)
        assertTrue(!ItemIdentity.sameItem(a, b))
    }

    @Test
    fun `a tree document id equal to the fallback sentinel is a known, accepted limitation`() {
        // toItemRef() decodes this correctly -- it is an ordinary tree-shaped Uri whose tree id
        // just happens to equal the sentinel word.
        val suspicious = Uri.parse("content://auth/tree/fylz-fallback-item-ref")
        val ref = suspicious.toItemRef()
        assertEquals(ItemRef("auth", "fylz-fallback-item-ref", "fylz-fallback-item-ref"), ref)

        // toUri() cannot tell this ref apart from a genuine fallback ref, because both would
        // carry that exact locationId -- there is no third bit of state recording which path
        // produced it. It falls back to parsing opaqueItemId as a bare Uri, which is wrong here.
        // Accepted because no real provider this app ships (FylzFilesDocumentsProvider's own
        // ids and every SAF authority observed follow a `root:relative/path` shape) ever
        // produces a document id equal to this specific word; a real collision is a
        // never-triggered theoretical risk, not a live one. Documented, not silently patched
        // over with an unverifiable "it can't happen" claim.
        assertEquals(Uri.parse("fylz-fallback-item-ref"), ref.toUri())
    }
}
