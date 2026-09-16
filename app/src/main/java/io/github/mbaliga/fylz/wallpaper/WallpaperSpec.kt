package io.github.mbaliga.fylz.wallpaper

import android.net.Uri

/**
 * What the desktop (and optionally the phone) paints behind everything else. Exactly one spec is
 * active at a time, persisted by [WallpaperPreferences] and rendered by
 * [io.github.mbaliga.fylz.ui.desktop.WallpaperLayer].
 */
sealed interface WallpaperSpec {
    /** No wallpaper — the surface underneath (the theme's own background) shows through. */
    object None : WallpaperSpec

    /** A single flat colour, keyed by [slug] into [io.github.mbaliga.fylz.ui.desktop.WallpaperLayer]'s fixed palette. */
    data class Solid(val slug: String) : WallpaperSpec

    /** A two-colour gradient, keyed by [slug] into the same fixed palette as [Solid]. */
    data class Gradient(val slug: String) : WallpaperSpec

    /**
     * A user-picked image. [blur] softens it; [dim] (clamped 0f..0.6f) darkens it under a scrim.
     * The persistable read grant on [uri] is taken once, at pick time, by
     * [io.github.mbaliga.fylz.ui.desktop.WallpaperPickerSheet]'s `OpenDocument` callback — this
     * spec itself carries no grant logic. [WallpaperPreferences.validateGrant] re-checks that
     * grant on read and degrades to [None] if it's gone.
     */
    data class Image(val uri: Uri, val blur: Boolean, val dim: Float) : WallpaperSpec

    /** The Animalcules pond-water live render — ambient water only, deliberately no critters. */
    object PondWater : WallpaperSpec
}

/**
 * The Animalcules Play Store listing, linked from the pond-water attribution chip
 * ([io.github.mbaliga.fylz.ui.desktop.WallpaperLayer]) and the picker sheet's "Get Animalcules"
 * action ([io.github.mbaliga.fylz.ui.desktop.WallpaperPickerSheet]) — one constant so the two
 * call sites can't drift.
 */
internal const val ANIMALCULES_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=app.animalcules"
