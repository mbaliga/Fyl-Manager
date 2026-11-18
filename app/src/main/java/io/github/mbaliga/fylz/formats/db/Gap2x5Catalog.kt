package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*

/** Photoshop resources, painting apps, camera sidecars and cine RAW, scientific and 8/16-bit computer images. */
object Gap2x5Catalog {

    private fun f(
        id: String, label: String, cat: FormatCategory, ext: List<String> = listOf(id),
        mime: List<String> = emptyList(), sigs: List<Signature> = emptyList(),
        container: String? = null, text: Boolean = false, d: String = "",
    ) = FormatSpec(id, label, cat, ext, mime, sigs, container, text, d)

    private val I = FormatCategory.IMAGE
    private val R = FormatCategory.RAW_PHOTO

    val specs: List<FormatSpec> = listOf(
        // ---- Photoshop resources ----
        f("aco", "Photoshop swatches and color tables", I, listOf("aco", "act", "acv", "csf"),
            d = "Adobe color swatches (aco), color table (act), curves (acv) and color settings (csf)."),
        f("abr", "Photoshop brushes, styles and actions", I, listOf("abr", "asl", "atn"),
            d = "Adobe brush (abr), layer style (asl) and action (atn) libraries."),
        f("8bf", "Photoshop plugin", I, listOf("8bf", "8bi", "8ba", "8be"),
            d = "Photoshop plug-in modules: filter, import, automation and export."),
        f("psdt", "Photoshop template / cloud document", I, listOf("psdt", "psdc")),
        f("pdd", "PhotoDeluxe / Photoshop legacy image", I, sigs = listOf(sig(0, "8BPS"))),
        // ---- Painting applications ----
        f("pxm", "Pixelmator document", I, listOf("pxm", "pixelmator")),
        f("acorn", "Acorn image", I, d = "Flying Meat Acorn document."),
        f("tvpp", "TVPaint project", I, listOf("tvpp", "tvp"), d = "TVPaint Animation project."),
        f("ptg", "ArtRage painting", I),
        f("gpl", "GIMP palette", I, mime = listOf("application/x-gimp-palette"),
            sigs = listOf(sig(0, "GIMP Palette")), text = true,
            d = "Plain-text palette used by GIMP, Inkscape and Krita."),
        f("gih", "GIMP brush pipe", I, listOf("gih", "vbr"),
            d = "GIMP image hose (gih) and variable brush (vbr)."),
        // ---- Camera sidecars and cine RAW ----
        f("cof", "Capture One settings", R, listOf("cof", "comask", "cot"),
            d = "Capture One sidecar settings, masks and session templates."),
        f("aae", "Apple Photos adjustments", R, text = true, d = "Sidecar holding non-destructive edits."),
        f("pp3", "RawTherapee processing profile", R, text = true, d = "INI-style development profile."),
        f("crm", "Canon Cinema RAW Light", R, d = "Canon Cinema RAW Light clip."),
        f("cine", "Phantom Cine video", R, d = "Vision Research Phantom high-speed camera recording."),
        f("rdc", "RED camera clip", R, listOf("rdc"), d = "RED camera clip folder and metadata."),
        f("arx", "ARRIRAW", R, d = "ARRI camera RAW image sequence frame."),
        f("eip", "Phase One enhanced image package", R),
        f("thm", "Camera thumbnail", I, d = "Small thumbnail sidecar written by cameras next to a video clip."),
        f("ctg", "Canon catalog", R),
        f("lrdata", "Lightroom previews", R, listOf("lrdata", "lrprev"),
            d = "Adobe Lightroom preview cache."),
        f("dxo", "DxO ONE RAW image", R, d = "DxO ONE camera RAW image (DNG-based)."),
        // ---- Scientific and legacy images ----
        f("jls", "JPEG-LS", I, mime = listOf("image/jls"),
            sigs = listOf(sigHex(0, "FF D8 FF F7")),
            d = "Lossless/near-lossless JPEG-LS (SOF55 marker)."),
        f("sfw", "SeattleFilmWorks image", I, d = "Modified JPEG used by SeattleFilmWorks."),
        f("vic", "VICAR image", I, listOf("vic", "vicar"),
            sigs = listOf(sig(0, "LBLSIZE=")),
            d = "JPL VICAR image with an ASCII label."),
        f("pds", "NASA PDS image", I, d = "Planetary Data System image with an ODL label."),
        f("rle", "Utah RLE image", I, d = "Utah Raster Toolkit run-length encoded image."),
        f("mtv", "MTV raytracer image", I),
        f("six", "Sixel graphics", I, listOf("six", "sixel"), d = "DEC terminal sixel bitmap stream."),
        f("ans", "ANSI art", I, listOf("ans", "ansi"), d = "Text art using ANSI escape sequences."),
        f("xb", "XBIN text art", I, listOf("xb", "xbin"),
            sigs = listOf(sigHex(0, "58 42 49 4E 1A")),
            d = "eXtended BIN text-mode image."),
        f("pxr", "Pixar picture", I),
        // ---- 8/16-bit computer images ----
        f("ham", "Amiga HAM image", I, listOf("ham"), d = "Hold-And-Modify Amiga image."),
        f("neo", "Atari NEOchrome", I),
        f("pi1", "Atari DEGAS", I, listOf("pi1", "pi2", "pi3", "pc1", "pc2", "pc3"),
            d = "DEGAS (pi*) and compressed DEGAS Elite (pc*) images."),
        f("tny", "Atari Tiny", I, listOf("tny", "tn1", "tn2", "tn3")),
        f("doo", "Atari Doodle", I),
        f("koa", "Koala Painter C64", I, listOf("koa", "kla"), d = "Commodore 64 multicolor bitmap."),
        f("cdu", "C64 CDU-Paint image", I),
        f("dd", "C64 Doodle", I, listOf("dd", "ddl"), d = "Commodore 64 Doodle hires bitmap."),
        f("ocp", "C64 Art Studio", I, d = "Commodore 64 Art Studio hires bitmap."),
        f("sc5", "MSX2 screen", I, listOf("sc5", "sc7", "sc8", "sca"), d = "MSX2 SCREEN 5/7/8/10-12 VRAM dump."),
    )
}
