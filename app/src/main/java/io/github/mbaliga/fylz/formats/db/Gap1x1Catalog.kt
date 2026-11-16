package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*

/** NLE, VFX, compositing, DAW and game-audio project/asset formats. */
object Gap1x1Catalog {

    private fun f(
        id: String, label: String, cat: FormatCategory, ext: List<String> = listOf(id),
        mime: List<String> = emptyList(), sigs: List<Signature> = emptyList(),
        container: String? = null, text: Boolean = false, d: String = "",
    ) = FormatSpec(id, label, cat, ext, mime, sigs, container, text, d)

    private val A = FormatCategory.AUDIO
    private val V = FormatCategory.VIDEO
    private val M = FormatCategory.MODEL_3D
    private val I = FormatCategory.IMAGE
    private val N = FormatCategory.MUSIC_NOTATION

    val specs: List<FormatSpec> = listOf(
        // ---- Adobe ----
        f("aep", "Adobe After Effects project", V, listOf("aep", "aepx"),
            sigs = listOf(sigHex(0, "52 49 46 58") + sig(8, "Egg!")),
            d = "Binary RIFX project (.aep) or XML project (.aepx)."),
        f("aet", "After Effects template", V, d = "After Effects project saved as a template."),
        f("prproj", "Adobe Premiere Pro project", V, d = "Gzip-compressed XML project."),
        f("ffx", "After Effects animation preset", V,
            sigs = listOf(sigHex(0, "52 49 46 58") + sig(8, "FaFX")),
            d = "RIFX-based animation preset."),
        f("mogrt", "Motion Graphics template", V, d = "Premiere Pro Essential Graphics template package."),
        // ---- Apple ----
        f("fcpxml", "Final Cut Pro XML", V, listOf("fcpxml"), listOf("application/xml"), text = true,
            d = "Final Cut Pro X interchange XML."),
        f("fcpbundle", "Final Cut Pro X library", V, d = "Library bundle (a directory package)."),
        f("fcp", "Final Cut Pro 7 project", V),
        f("imovieproj", "iMovie project", V),
        f("motn", "Apple Motion project", V, text = true, d = "XML-based Motion project."),
        // ---- Other NLEs ----
        f("drp", "DaVinci Resolve project archive", V),
        f("veg", "Sony Vegas project", V),
        f("kdenlive", "Kdenlive project", V, mime = listOf("application/x-kdenlive"), text = true,
            d = "XML project based on MLT."),
        f("mlt", "MLT XML project", V, text = true, d = "Media Lovin' Toolkit XML description."),
        f("vpj", "VideoPad project", V),
        f("wve", "Wondershare Filmora project", V),
        // ---- Compositing / VFX ----
        f("nk", "Nuke script", V, listOf("nk", "gizmo"), text = true,
            d = "Foundry Nuke node-graph script or gizmo."),
        f("hda", "Houdini digital asset", M, listOf("hda", "otl"),
            d = "SideFX Houdini digital asset (scenes .hip/.hipnc/.hiplc are in CadModelCatalog)."),
        f("bgeo", "Houdini geometry", M),
        f("vdb", "OpenVDB volume", M,
            sigs = listOf(sigHex(0, "20 42 44 56 00 00 00 00")),
            d = "Sparse volumetric grid; file starts with the 64-bit magic 0x56444220."),
        // ---- Interchange / editorial ----
        f("aaf", "Advanced Authoring Format", V, d = "Post-production metadata interchange."),
        f("omf", "Open Media Framework", V, d = "Avid-originated media interchange."),
        f("otio", "OpenTimelineIO timeline", V, text = true, d = "JSON editorial timeline interchange."),
        f("edl", "Edit decision list", V, text = true),
        f("ale", "Avid Log Exchange", V, text = true, d = "Tab-delimited clip log."),
        f("look", "Color look file", V, text = true),
        f("3dl", "3D LUT", V, text = true, d = "Text-based 3D colour lookup table."),
        f("gxf", "General Exchange Format video", V, mime = listOf("application/gxf")),
        f("rla", "Wavefront RLA image", I, mime = listOf("image/x-rla")),
        // ---- DAW / audio ----
        f("reason", "Reason song", A),
        f("rns", "Reason song (legacy)", A),
        f("xrns", "Renoise song", A, d = "Zip package containing Song.xml."),
        f("sfk", "Sound Forge peak file", A),
        f("sd2", "Sound Designer II", A, d = "Digidesign audio file stored in a Mac resource fork."),
        f("bww", "Bagpipe Music Writer", A, text = true),
        f("mus", "Finale legacy score", N),
        f("tg", "TuxGuitar", N),
        // ---- Game audio ----
        f("wem", "Wwise encoded media", A),
        f("bank", "FMOD sound bank", A,
            sigs = listOf(sigHex(0, "52 49 46 46") + sig(8, "FEV ")),
            d = "RIFF container of form type FEV."),
        f("xsb", "XACT sound bank", A,
            sigs = listOf(sig(0, "SDBK")),
            d = "XACT sound bank (SDBK); wave banks (.xwb) are defined in GamesNicheCatalog."),
        f("hca", "CRI HCA audio", A, sigs = listOf(sigHex(0, "48 43 41 00"))),
        f("acb", "CRI ADX2 cue sheet", A,
            sigs = listOf(sig(0, "@UTF")),
            d = "CRI @UTF table cue sheet; the .awb wave archive (AFS2) is a separate format."),
    )
}
