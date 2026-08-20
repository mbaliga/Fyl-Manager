package io.github.mbaliga.fylz.wallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Store-level guarantees on top of [WallpaperSpecCodecTest]'s per-variant codec pins: a fresh
 * instance sees a prior instance's write, a torn/corrupted current value recovers from the
 * backup slot the way [io.github.mbaliga.fylz.staging.ShelfStore] does, and
 * [WallpaperPreferences.validateGrant] degrades an [WallpaperSpec.Image] whose read grant is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperPreferencesTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = WallpaperPreferences(context)
    private fun rawPrefs() = context.getSharedPreferences("fylz_wallpaper", Context.MODE_PRIVATE)

    @Test
    fun `a spec set by one store instance is visible to a fresh instance over the same preferences`() {
        store().setSpec(WallpaperSpec.Gradient("dusk"))
        assertEquals(WallpaperSpec.Gradient("dusk"), store().spec())
    }

    @Test
    fun `setSpec twice promotes the first write into the backup slot`() {
        val store = store()
        store.setSpec(WallpaperSpec.Solid("ink"))
        store.setSpec(WallpaperSpec.Solid("clay"))

        assertEquals("v1|solid|ink", rawPrefs().getString("spec:backup", null))
        assertEquals("v1|solid|clay", rawPrefs().getString("spec", null))
    }

    @Test
    fun `a corrupted current value falls back to the last known good backup`() {
        val store = store()
        store.setSpec(WallpaperSpec.Solid("sand")) // promoted to the backup by the write below
        store.setSpec(WallpaperSpec.Solid("sky"))

        rawPrefs().edit().putString("spec", "garbage, not a spec at all").commit()

        assertEquals(WallpaperSpec.Solid("sand"), store.spec())
    }

    @Test
    fun `when both current and backup are corrupted, spec falls back to None rather than throwing`() {
        rawPrefs().edit()
            .putString("spec", "garbage")
            .putString("spec:backup", "also garbage")
            .commit()

        assertEquals(WallpaperSpec.None, store().spec())
    }

    @Test
    fun `validateGrant leaves a non-Image spec untouched`() {
        val store = store()
        store.setSpec(WallpaperSpec.PondWater)

        assertEquals(WallpaperSpec.PondWater, store.validateGrant(context))
    }

    @Test
    fun `validateGrant keeps an Image spec whose read grant is still held`() {
        val store = store()
        val uri = Uri.parse("content://com.android.providers.media.documents/document/image%3A8")
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        store.setSpec(WallpaperSpec.Image(uri, blur = true, dim = 0.4f))

        assertEquals(WallpaperSpec.Image(uri, blur = true, dim = 0.4f), store.validateGrant(context))
    }

    @Test
    fun `validateGrant degrades an Image whose read grant is gone, and rewrites the pref`() {
        val store = store()
        val uri = Uri.parse("content://com.android.providers.media.documents/document/image%3A7")
        // No takePersistableUriPermission call for this uri -- persistedUriPermissions is empty.
        store.setSpec(WallpaperSpec.Image(uri, blur = false, dim = 0.2f))

        val validated = store.validateGrant(context)

        assertEquals(WallpaperSpec.None, validated)
        assertEquals(WallpaperSpec.None, store.spec())
        assertEquals("v1|none", rawPrefs().getString("spec", null))
    }
}
