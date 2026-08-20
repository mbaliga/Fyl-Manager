package io.github.mbaliga.fylz.wallpaper

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WallpaperPreferences]' pipe-delimited codec, pinned variant by variant. The codec is private
 * to the store (same as [io.github.mbaliga.fylz.staging.ShelfStore]'s JSON codec is private to
 * `ShelfStore`), so these drive it through the public `setSpec`/`spec` round trip and, for the
 * malformed/forward-compat cases, by writing straight into the backing SharedPreferences file --
 * the same move [io.github.mbaliga.fylz.staging.ShelfStoreTest] makes to poke in a corrupted
 * manifest. There is no pure-JVM seam into the codec without a `Context`, so like every other
 * store test in this codebase, this one runs on Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperSpecCodecTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = WallpaperPreferences(context)
    private fun rawPrefs() = context.getSharedPreferences("fylz_wallpaper", Context.MODE_PRIVATE)

    @Test
    fun `None round-trips`() {
        val store = store()
        store.setSpec(WallpaperSpec.None)
        assertEquals(WallpaperSpec.None, store.spec())
    }

    @Test
    fun `Solid round-trips its slug`() {
        val store = store()
        store.setSpec(WallpaperSpec.Solid("moss"))
        assertEquals(WallpaperSpec.Solid("moss"), store.spec())
    }

    @Test
    fun `Gradient round-trips its slug`() {
        val store = store()
        store.setSpec(WallpaperSpec.Gradient("dawn"))
        assertEquals(WallpaperSpec.Gradient("dawn"), store.spec())
    }

    @Test
    fun `PondWater round-trips`() {
        val store = store()
        store.setSpec(WallpaperSpec.PondWater)
        assertEquals(WallpaperSpec.PondWater, store.spec())
    }

    @Test
    fun `Image round-trips its uri, blur and dim`() {
        val store = store()
        val uri = Uri.parse("content://com.android.providers.media.documents/document/image%3A42")
        store.setSpec(WallpaperSpec.Image(uri, blur = true, dim = 0.3f))

        assertEquals(WallpaperSpec.Image(uri, blur = true, dim = 0.3f), store.spec())
    }

    @Test
    fun `Image dim is clamped to 0f, 0_6f on the way in`() {
        val store = store()
        val uri = Uri.parse("content://x/document/1")
        store.setSpec(WallpaperSpec.Image(uri, blur = false, dim = 5f))

        assertEquals(0.6f, (store.spec() as WallpaperSpec.Image).dim, 0.0001f)
    }

    @Test
    fun `a literal pipe inside the image uri does not corrupt the field split`() {
        val store = store()
        // A raw '|' in the uri's own text, not percent-escaped by the caller -- exactly what
        // encode() has to protect against by percent-encoding the whole uri segment itself.
        val uri = Uri.parse("content://x/document/name|with|pipes")
        store.setSpec(WallpaperSpec.Image(uri, blur = false, dim = 0.1f))

        assertEquals(uri, (store.spec() as WallpaperSpec.Image).uri)
    }

    @Test
    fun `a malformed payload decodes to None`() {
        rawPrefs().edit().putString("spec", "not even close to valid").commit()
        assertEquals(WallpaperSpec.None, store().spec())
    }

    @Test
    fun `an unrecognised variant tag decodes to None`() {
        rawPrefs().edit().putString("spec", "v1|holographic|foo").commit()
        assertEquals(WallpaperSpec.None, store().spec())
    }

    @Test
    fun `a future schema version decodes to None rather than crashing -- forward compat`() {
        rawPrefs().edit().putString("spec", "v2|pond|some|new|shape").commit()
        assertEquals(WallpaperSpec.None, store().spec())
    }

    @Test
    fun `a fresh store with nothing persisted reads as None`() {
        assertEquals(WallpaperSpec.None, store().spec())
    }
}
