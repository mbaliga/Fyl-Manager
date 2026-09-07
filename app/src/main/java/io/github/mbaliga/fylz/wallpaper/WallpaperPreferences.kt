package io.github.mbaliga.fylz.wallpaper

import android.content.Context
import android.net.Uri

/**
 * Persists the desktop's active [WallpaperSpec] to its own SharedPreferences file.
 *
 * Same idiom as [io.github.mbaliga.fylz.staging.ShelfStore] / [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]:
 * one flat string under [SPEC_KEY], the last parseable write retained under [BACKUP_KEY] so a
 * torn write never strands the desktop with an unparseable pref, `@Synchronized` methods,
 * `editor.commit()` (never `apply`) so a caller that reads right back after writing always sees
 * its own write.
 *
 * The encoding is a schema-versioned, pipe-delimited string rather than JSON — there is exactly
 * one value to encode and the five variants differ enough in shape (no payload, one slug, an URI
 * plus two flags) that a single line reads easier than a one-key JSON object would:
 * ```
 * v1|none
 * v1|solid|moss
 * v1|gradient|dawn
 * v1|image|<uri>|blur=1|dim=0.30
 * v1|pond
 * ```
 * The URI segment is percent-encoded ([Uri.encode]) so a `|` inside a content URI's query string
 * can never be mistaken for a field separator. A malformed payload, an unrecognised variant tag,
 * or a schema version this build has never seen ([decode]'s leading `parts[0] != SCHEMA_V1` check)
 * all decode to `null` from [decode] — forward-compat, not a crash — and [spec] falls back to the
 * backup slot and then to [WallpaperSpec.None].
 */
class WallpaperPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun spec(): WallpaperSpec {
        val current = decode(preferences.getString(SPEC_KEY, null))
        if (current != null) return current
        return decode(preferences.getString(BACKUP_KEY, null)) ?: WallpaperSpec.None
    }

    /**
     * Persists [spec]. For [WallpaperSpec.Image] this does NOT take the persistable read grant on
     * [WallpaperSpec.Image.uri] — that happens once, at pick time, in
     * [io.github.mbaliga.fylz.ui.desktop.WallpaperPickerSheet]'s `OpenDocument` callback (the
     * pattern at `FylzV1App.kt:421-432`, adapted to a read-only single-document grant). Calling
     * this alone with an un-granted uri stores a spec that [validateGrant] will degrade back to
     * [WallpaperSpec.None] the next time anyone checks.
     */
    @Synchronized
    fun setSpec(spec: WallpaperSpec) {
        persist(spec)
    }

    /**
     * Re-checks a persisted [WallpaperSpec.Image]'s read grant against
     * [android.content.ContentResolver.getPersistedUriPermissions] — the same check
     * `FylzV1App.kt:389-407` runs for the landing subject's tree grant — and degrades to
     * [WallpaperSpec.None], rewriting the pref, when the grant is gone (the picked file's
     * provider revoked it, the app was reinstalled, etc.). Every other variant is returned
     * unchanged; this is a cheap no-op read for them. Call this once at the point a spec is about
     * to actually be rendered or applied (a composition root, a wallpaper service's config
     * reload), not on every frame.
     */
    @Synchronized
    fun validateGrant(context: Context): WallpaperSpec {
        val current = spec()
        val image = current as? WallpaperSpec.Image ?: return current
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == image.uri && it.isReadPermission
        }
        if (granted) return current
        persist(WallpaperSpec.None)
        return WallpaperSpec.None
    }

    private fun persist(spec: WallpaperSpec) {
        val encoded = encode(spec)
        val currentRaw = preferences.getString(SPEC_KEY, null)
        val editor = preferences.edit().putString(SPEC_KEY, encoded)

        // Retain the last parseable spec, exactly as ShelfStore retains the Shelf's: a partially
        // written or externally corrupted current value must never replace the only known-good one.
        if (!currentRaw.isNullOrBlank() && decode(currentRaw) != null) {
            editor.putString(BACKUP_KEY, currentRaw)
        }

        check(editor.commit()) { "Unable to persist the wallpaper spec." }
    }

    private fun encode(spec: WallpaperSpec): String = when (spec) {
        is WallpaperSpec.None -> "$SCHEMA_V1|none"
        is WallpaperSpec.Solid -> "$SCHEMA_V1|solid|${spec.slug}"
        is WallpaperSpec.Gradient -> "$SCHEMA_V1|gradient|${spec.slug}"
        is WallpaperSpec.PondWater -> "$SCHEMA_V1|pond"
        is WallpaperSpec.Image -> {
            val dim = spec.dim.coerceIn(0f, 0.6f)
            "$SCHEMA_V1|image|${Uri.encode(spec.uri.toString())}|blur=${if (spec.blur) 1 else 0}|dim=$dim"
        }
    }

    /** Null only when a non-empty payload fails to parse — mirrors ShelfStore/CanvasLayoutStore. */
    private fun decode(raw: String?): WallpaperSpec? {
        if (raw.isNullOrBlank()) return WallpaperSpec.None
        val parts = raw.split("|")
        if (parts.size < 2 || parts[0] != SCHEMA_V1) return null
        return runCatching {
            when (parts[1]) {
                "none" -> WallpaperSpec.None
                "pond" -> WallpaperSpec.PondWater
                "solid" -> WallpaperSpec.Solid(parts[2])
                "gradient" -> WallpaperSpec.Gradient(parts[2])
                "image" -> {
                    val uri = Uri.parse(Uri.decode(parts[2]))
                    val flags = parts.drop(3)
                    val blur = flags.firstOrNull { it.startsWith("blur=") }?.substringAfter('=') == "1"
                    val dim = flags.firstOrNull { it.startsWith("dim=") }
                        ?.substringAfter('=')
                        ?.toFloatOrNull()
                        ?: 0f
                    WallpaperSpec.Image(uri, blur, dim.coerceIn(0f, 0.6f))
                }
                else -> null
            }
        }.getOrNull()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_wallpaper"
        const val SPEC_KEY = "spec"
        const val BACKUP_KEY = "spec:backup"
        const val SCHEMA_V1 = "v1"
    }
}
