package io.github.mbaliga.fylz.appearance

/**
 * What a folder is *made of*, as opposed to what colour it is.
 *
 * The owner's ask was a sheet of photoreal folder renders -- gloss, brushed metal, kraft, leather,
 * carbon, neon -- with "I want at least this many styles". This is that axis, built the way the
 * rest of folder appearance already works: a finish is chosen per folder and combines with the
 * folder's own [FolderPalette] tone, so the catalogue is a product rather than a list. Fourteen
 * finishes against fourteen tones is 196 folder looks, from one recipe each.
 *
 * They are drawn, not photographed. Every finish is a layered procedural recipe -- gradients,
 * sheens, a deterministic grain table, edge strokes -- resolved against whatever tone the folder
 * carries, which is what lets any finish take any colour. That is a real difference from the
 * reference sheet, which is 27 fixed renders: these hold up under a colour change and under dark
 * mode, and they do not ship a megabyte of PNGs, but a photoreal render they are not.
 *
 * [DEFAULT] is deliberately first and deliberately plain: a folder with no finish chosen draws
 * exactly as it did before this axis existed.
 */
enum class FolderFinish(val slug: String, val label: String) {
    /** Flat paint with a whisper of top-down shading. What every folder drew before finishes. */
    DEFAULT("default", "Matte"),

    /** Low-contrast vertical sheen -- eggshell rather than flat. */
    SATIN("satin", "Satin"),

    /** Lacquered: a hard specular band across the upper third and a lit top edge. */
    GLOSS("gloss", "Gloss"),

    /** Injection-moulded toy plastic: a soft radial hotspot off the top-left and a saturated rim. */
    PLASTIC("plastic", "Plastic"),

    /** Brushed aluminium: fine vertical banding, desaturated, one bright horizontal band. */
    BRUSHED("brushed", "Brushed metal"),

    /** Mirror-polished: the light/dark/light horizon a chrome bar reflects. */
    CHROME("chrome", "Chrome"),

    /** Anodised: deep saturated metal, dark at the edges, a narrow bright sheen. */
    ANODISED("anodised", "Anodised"),

    /** Kraft stock: warm, desaturated, fibre speckle, a shaded bottom edge. */
    PAPER("paper", "Kraft paper"),

    /** Corrugated board: kraft, with the fluting showing along the bottom edge. */
    CARDBOARD("cardboard", "Cardboard"),

    /** Grain running the long way, warm and banded. */
    WOOD("wood", "Wood"),

    /** Pebbled hide with a stitched inset seam. */
    LEATHER("leather", "Leather"),

    /** 2x2 twill weave, near-black, tinted only slightly by the folder's tone. */
    CARBON("carbon", "Carbon fibre"),

    /** Translucent pane with a lit rim -- the flagship theme's glass, offered per folder. */
    GLASS("glass", "Glass"),

    /** Dark body, glowing rim in the folder's own colour. */
    NEON("neon", "Neon"),
    ;

    companion object {
        /** Resolves a stored slug, tolerating a record written by a newer build than this one. */
        fun fromSlug(slug: String?): FolderFinish? = slug?.let { value -> entries.firstOrNull { it.slug == value } }
    }
}
