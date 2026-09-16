package io.github.mbaliga.fylz.appearance

import androidx.compose.ui.graphics.Color

/**
 * One folder's chosen identity, persisted by [FolderAppearanceStore]. Every field is independently
 * optional -- a folder with no record at all and one whose fields are all null draw identically,
 * which is what lets a plain [io.github.mbaliga.fylz.ui.components.FolderFace] read a lookup that
 * missed the same way it reads one that hit but customised nothing.
 *
 * [finishSlug] names a [FolderFinish] the same way -- what the folder is made of, as opposed to
 * what colour it is. Null means "whatever the theme's own material draws", so a folder that has
 * only ever been given a colour is untouched by the finish axis existing.
 *
 * [colorSlug] names a [FolderPalette] entry rather than carrying a raw ARGB value: a slug is
 * resolved against whichever colour scheme is in force when it is drawn, so a folder painted
 * under a dark wallpaper still reads correctly if the user switches to light (or the wallpaper
 * itself changes) -- a stored [Int] would freeze in whichever mode it was chosen in.
 */
data class FolderAppearance(
    val iconKey: String? = null,
    val colorSlug: String? = null,
    val stickers: List<String> = emptyList(),
    val finishSlug: String? = null,
)

/** How many stickers a single folder's glass carries -- past this the cascade in the pane just crowds the name panel. */
const val MAX_FOLDER_STICKERS = 6

/**
 * The fixed set of [FolderAppearance.colorSlug] values and the real [Color] each resolves to,
 * light and dark. [CORE_SLUGS] is what every theme with a colour-bearing folder body ([FolderMaterial.SOLID]
 * or [FolderMaterial.FROSTED][io.github.mbaliga.fylz.ui.theme.FolderMaterial]) can offer; the
 * flagship Fylz theme alone reaches the full catalogue -- "a much wider range of colours" is this
 * list being twice the length, not a different mechanism.
 *
 * Hand-authored pairs rather than derived tones: these are a folder's own paint, not a Material
 * role, so they hold still regardless of accent preset or dynamic wallpaper colour.
 */
object FolderPalette {
    private data class Tone(val light: Color, val dark: Color)

    private val TONES: Map<String, Tone> = linkedMapOf(
        // -- core: offered under every theme with a tintable folder body --
        "blue" to Tone(Color(0xFFC8DBFF), Color(0xFF2C4A85)),
        "green" to Tone(Color(0xFFC9EAC9), Color(0xFF2E6B3C)),
        "amber" to Tone(Color(0xFFFFEEB0), Color(0xFF7A5F14)),
        "purple" to Tone(Color(0xFFEAC9F5), Color(0xFF632E7A)),
        "red" to Tone(Color(0xFFFFD6D6), Color(0xFF7A2E2E)),
        "teal" to Tone(Color(0xFFB9E7DE), Color(0xFF1F6B5C)),
        // -- extended: Fylz-only --
        "orange" to Tone(Color(0xFFFFE0C2), Color(0xFF7A4A1E)),
        "yellow" to Tone(Color(0xFFFBF3A0), Color(0xFF6E661A)),
        "lime" to Tone(Color(0xFFE3F0AE), Color(0xFF566E1E)),
        "cyan" to Tone(Color(0xFFBDEAF0), Color(0xFF1B6270)),
        "indigo" to Tone(Color(0xFFD2D3FF), Color(0xFF383A85)),
        "violet" to Tone(Color(0xFFE0CFFF), Color(0xFF52318A)),
        "pink" to Tone(Color(0xFFFAC9E4), Color(0xFF7A2E5C)),
        "rose" to Tone(Color(0xFFFFD1DC), Color(0xFF7A2E43)),
    )

    private val CORE_SLUGS: List<String> = listOf("blue", "green", "amber", "purple", "red", "teal")

    /** Every slug a picker may offer -- the full catalogue under [flagship], the six-tone core otherwise. */
    fun slugsFor(flagship: Boolean): List<String> = if (flagship) TONES.keys.toList() else CORE_SLUGS

    /** Resolves a stored [slug] to the real colour for [dark], or null for an unrecognised (or legacy-dropped) slug. */
    fun colorFor(slug: String, dark: Boolean): Color? = TONES[slug]?.let { if (dark) it.dark else it.light }
}

/**
 * The sticker catalogue: SVG assets under `assets/stickers/`, not emoji -- emoji cannot be tinted
 * and render differently per OEM, which would make the same folder look different on two phones.
 * Each key is the asset's basename; [io.github.mbaliga.fylz.ui.components.FolderFace] resolves it
 * to `stickers/<key>.svg` directly, no style-fallback swap the way [FolderAppearance.iconKey] needs
 * -- there is exactly one drawing of each sticker, not one per [io.github.mbaliga.fylz.ui.components.IconStyle].
 */
object FolderStickers {
    val ALL: List<String> = listOf("star", "heart", "flag", "bolt", "moon", "sun", "sparkle", "pin")
}
