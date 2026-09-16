package io.github.mbaliga.fylz.ui.components

import io.github.mbaliga.fylz.core.format.PreviewFamily

/**
 * Which visual treatment the file-type icons are drawn in. Chosen in Settings, or set by
 * [io.github.mbaliga.fylz.ui.theme.ThemeStyle] as a default the user may still override.
 *
 * [DEFAULT], [FILLED], [GRADIENT] and [GRAY] cover the whole catalog (minus the Design formats,
 * which have no Default artwork and fall back to [FILLED]). [VINTAGE] and [RETRO] are pixel-art
 * packs drawn for the theme system and cover only the generic families -- an exact format with
 * no pixel artwork of its own falls back to [FILLED] too. See [FileTypeIcons.assetPath].
 */
enum class IconStyle(val slug: String) {
    DEFAULT("default"),
    FILLED("filled"),
    GRADIENT("gradient"),
    GRAY("gray"),
    VINTAGE("vintage"),
    RETRO("retro"),
}

/**
 * Resolves a file to one of the Hyle reference-pack file-type icons, as an `assets/` path.
 *
 * ### Why this exists
 *
 * The app already knew far more about a file than it drew. [PreviewFamily] splits files twenty
 * ways and the format registry resolves an exact extension on top of that, but rendering collapsed
 * all of it back down to nine `EntryKind`s and then bottomed out at a single stock glyph — a
 * *sidebar* icon stood in for every unrecognised file. Resolution here runs the other way, most
 * specific first: exact format, then the family's generic, then the empty-document mark.
 *
 * Paths point at SVGs loaded through Coil's SVG decoder rather than vector drawables: the source
 * artwork uses masks, filters and clip-paths, none of which `VectorDrawable` can express, and the
 * pack's PNGs are 40x40 — too small for anything but a list row.
 *
 * Generated from the staged asset set; [EXACT] and [GENERIC] name files that are asserted to exist.
 */
object FileTypeIcons {

    /** Formats with artwork of their own, keyed by lowercase extension. */
    private val EXACT: Set<String> = setOf(
        "aep",
        "ai",
        "avi",
        "css",
        "csv",
        "dmg",
        "doc",
        "docx",
        "eps",
        "exe",
        "fig",
        "gif",
        "html",
        "img",
        "indd",
        "java",
        "jpeg",
        "jpg",
        "js",
        "json",
        "mp3",
        "mp4",
        "mpeg",
        "pdf",
        "png",
        "ppt",
        "pptx",
        "psd",
        "rar",
        "rss",
        "sql",
        "svg",
        "tiff",
        "txt",
        "wav",
        "webp",
        "xls",
        "xlsx",
        "xml",
        "zip",
    )

    /** Icons the pack ships without a Default treatment; they fall back to filled. */
    private val NO_DEFAULT: Set<String> = setOf(
        "aep",
        "ai",
        "fig",
        "indd",
        "psd",
        "simple-audio",
        "simple-code",
        "simple-document",
        "simple-empty",
        "simple-folder",
        "simple-image",
        "simple-pdf",
        "simple-video",
    )

    /**
     * Every key [IconStyle.VINTAGE] and [IconStyle.RETRO] ship artwork for -- the generic marks
     * [GENERIC] actually names, plus zip/sql/exe, drawn as hand-authored pixel art rather than
     * generated for all 47 formats. An extension outside this set (a `.docx`, a `.psd`) still
     * resolves correctly; it just does it in [IconStyle.FILLED] instead, via [STYLE_FALLBACK].
     *
     * internal, not private: FileTypeIconsTest asserts every member of this exact set has real
     * artwork on disk, and a hand-copied list in the test would silently stop tracking this one.
     */
    internal val PIXEL_KEYS: Set<String> = setOf(
        "simple-folder",
        "simple-empty",
        "simple-image",
        "simple-video",
        "simple-audio",
        "simple-document",
        "simple-code",
        "simple-pdf",
        "zip",
        "sql",
        "exe",
    )

    /** Where a style falls back once a key isn't in its own coverage. */
    private val STYLE_FALLBACK: Map<IconStyle, IconStyle> = mapOf(
        IconStyle.DEFAULT to IconStyle.FILLED,
        IconStyle.VINTAGE to IconStyle.FILLED,
        IconStyle.RETRO to IconStyle.FILLED,
    )

    /** Whether [style] ships its own artwork for [key], rather than needing [STYLE_FALLBACK]. */
    private fun IconStyle.covers(key: String): Boolean = when (this) {
        IconStyle.VINTAGE, IconStyle.RETRO -> key in PIXEL_KEYS
        IconStyle.DEFAULT -> key !in NO_DEFAULT
        IconStyle.FILLED, IconStyle.GRADIENT, IconStyle.GRAY -> true
    }

    /** The generic mark for each family, used when the exact format has no artwork. */
    private val GENERIC: Map<PreviewFamily, String> = mapOf(
        PreviewFamily.DIRECTORY to "simple-folder",
        PreviewFamily.MARKDOWN to "simple-document",
        PreviewFamily.TEXT to "simple-document",
        PreviewFamily.IMAGE to "simple-image",
        PreviewFamily.PDF to "simple-pdf",
        PreviewFamily.AUDIO to "simple-audio",
        PreviewFamily.VIDEO to "simple-video",
        PreviewFamily.ARCHIVE to "zip",
        PreviewFamily.FONT to "simple-document",
        PreviewFamily.OFFICE to "simple-document",
        PreviewFamily.EBOOK to "simple-document",
        PreviewFamily.MODEL_3D to "simple-empty",
        PreviewFamily.CAD_2D to "simple-empty",
        PreviewFamily.DATABASE to "sql",
        PreviewFamily.GEOSPATIAL to "simple-empty",
        PreviewFamily.SCIENTIFIC to "simple-empty",
        PreviewFamily.EXECUTABLE to "exe",
        PreviewFamily.PACKAGE to "zip",
        PreviewFamily.BINARY to "simple-empty",
        PreviewFamily.DESIGN to "fig",
    )

    /** Last resort: a blank document. Every family maps to something, so this is belt-and-braces. */
    private const val FALLBACK = "simple-empty"

    /** The pixel pack's source-code mark -- see [CODE_EXTENSIONS]. */
    private const val CODE_KEY = "simple-code"

    /**
     * Source-code extensions with no format-specific artwork of their own: without this set they
     * fall through to whichever family the format registry happened to classify them under (most
     * land in [io.github.mbaliga.fylz.core.format.PreviewFamily.TEXT] and draw the generic
     * document mark), even though the pack ships a dedicated [CODE_KEY] drawing for exactly this
     * case. Consulted after [EXACT] (a format with its own artwork still wins) and before
     * [GENERIC] (a generic family mark is the fallback of last resort, not first).
     */
    private val CODE_EXTENSIONS: Set<String> = setOf(
        "py", "kt", "kts", "rs", "go", "c", "cpp", "cc", "h", "hpp", "rb", "sh", "bash",
        "ts", "tsx", "jsx", "yaml", "yml", "toml", "ini", "gradle", "swift",
    )

    /**
     * The `assets/`-relative path of the icon for [extension] in [family], drawn in [style].
     *
     * @param extension the format registry's resolved extension, lowercase, may be blank.
     */
    fun assetPath(extension: String, family: PreviewFamily, style: IconStyle): String {
        val lowered = extension.lowercase()
        val key = lowered.takeIf { it in EXACT }
            ?: CODE_KEY.takeIf { lowered in CODE_EXTENSIONS }
            ?: GENERIC[family]
            ?: FALLBACK
        return assetPath(key, style)
    }

    /**
     * The same resolution as the three-arg overload, for a caller that already has the exact
     * catalog [key] it wants rather than a format to derive one from -- a folder's own icon
     * override, or any key [allKeys] names. An unrecognised [key] (a stale override from a since-
     * retired catalog entry) resolves as [FALLBACK] rather than naming a file that was never shipped.
     */
    fun assetPath(key: String, style: IconStyle): String {
        val safeKey = key.takeIf { it in ALL_KEYS } ?: FALLBACK
        val resolved = if (style.covers(safeKey)) style else STYLE_FALLBACK[style] ?: style
        return "filetype/${safeKey}_${resolved.slug}.svg"
    }

    // Built once rather than inside allKeys() itself: both assetPath overloads consult membership
    // on every call (a folder row's icon resolves through here once per recomposition, `remember`
    // notwithstanding), so this must not re-union four collections every time.
    private val ALL_KEYS: Set<String> = EXACT + GENERIC.values + FALLBACK + CODE_KEY

    /** Every icon this catalog can name, for tests, the settings preview, and the folder-icon picker. */
    fun allKeys(): Set<String> = ALL_KEYS
}
