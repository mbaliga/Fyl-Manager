package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatCategory
import io.github.mbaliga.fylz.formats.FormatCategory.AUDIO
import io.github.mbaliga.fylz.formats.FormatSpec
import io.github.mbaliga.fylz.formats.sig
import io.github.mbaliga.fylz.formats.sigHex

/** DAW projects, sampler/instrument presets, tracker modules, AdLib and console sound-rip formats. */
object Gap2x1Catalog {
    private fun a(
        id: String, label: String, exts: List<String> = listOf(id), sigs: List<io.github.mbaliga.fylz.formats.Signature> = emptyList(),
        text: Boolean = false, desc: String = "", cat: FormatCategory = AUDIO, mime: List<String> = emptyList(),
    ) = FormatSpec(id, label, cat, exts, mime, sigs, null, text, desc)

    private fun psf(id: String, label: String, exts: List<String>, ver: String, desc: String) =
        a(id, label, exts, listOf(sigHex(0, "50 53 46 $ver")), desc = desc)

    val specs: List<FormatSpec> = listOf(
        // DAW / production
        a("bwproject", "Bitwig Studio project (BtWg header, shared by all Bitwig file types)", sigs = listOf(sig(0, "BtWg"))),
        a("bwpreset", "Bitwig preset"),
        a("bwclip", "Bitwig clip"),
        a("nks", "Native Instruments NKS preset", listOf("nks", "nksf", "nksn", "nksr")),
        a("nkx", "Kontakt resource container", listOf("nkx", "nkc")),
        a("ncw", "Native Instruments compressed wave"),
        a("nmsv", "Massive preset"),
        a("ens", "Reaktor ensemble"),
        a("nml", "Traktor collection", text = true, desc = "XML collection database"),
        a("gig", "GigaStudio instrument", desc = "RIFF 'DLS ' container; indistinguishable from plain DLS by magic, so matched by extension only"),
        a("sf3", "SoundFont 3 compressed", desc = "SoundFont 2 variant with Ogg Vorbis compressed samples"),
        a("sfark", "sfArk compressed SoundFont"),
        a("akp", "Akai S5000/S6000 program"),
        a("a3p", "Akai S3000 program", listOf("a3p", "a3s", "a3k")),
        a("exs-logic", "Logic EXS24 instrument", listOf("exs")),
        a("vstsound", "HALion VST Sound container"),
        a("vital", "Vital synth preset", listOf("vital", "vitalbank", "vitaltable"), text = true, desc = "JSON-based preset"),
        a("amxd", "Max for Live device", sigs = listOf(sig(0, "ampf"))),
        a("maxproj", "Max project"),
        a("pd", "Pure Data patch", listOf("pd", "pd_darwin"), listOf(sig(0, "#N canvas")), text = true),
        a("scsyndef", "SuperCollider synthdef", sigs = listOf(sig(0, "SCgf"))),
        // Additional well-attested formats
        a("alp", "Ableton Live pack"),
        a("rsid", "Commodore 64 RSID music", listOf("rsid"), listOf(sig(0, "RSID"))),
        // Trackers
        a("mt2", "MadTracker module", listOf("mt2", "mtp"), listOf(sig(0, "MT20"))),
        a("ams", "Velvet Studio tracker module", sigs = listOf(sig(0, "AMShdr"))),
        a("dmf", "X-Tracker module", sigs = listOf(sig(0, "DDMF"))),
        a("gdm", "General Digital Music module", sigs = listOf(sigHex(0, "47 44 4D FE") + sig(0x44, "GMFS"))),
        a("imf", "Imago Orpheus module", sigs = listOf(sig(60, "IM10"))),
        a("j2b", "Jazz Jackrabbit 2 music", sigs = listOf(sig(0, "MUSE"))),
        a("mptm", "OpenMPT module"),
        a("plm", "Disorder Tracker 2 module", sigs = listOf(sigHex(0, "50 4C 4D 1A"))),
        a("symmod", "Symphonie module"),
        // AdLib / OPL
        a("rol", "AdLib Visual Composer music"),
        a("cmf", "Creative Music File", sigs = listOf(sig(0, "CTMF"))),
        a("dro", "DOSBox raw OPL capture", sigs = listOf(sig(0, "DBRAWOPL"))),
        a("d00", "EdLib tracker module"),
        a("a2m", "AdLib Tracker 2 module", sigs = listOf(sig(0, "_A2module_"))),
        a("laa", "LucasArts AdLib music"),
        // Console sound rips (PSF family)
        psf("2sf", "Nintendo DS sound format", listOf("2sf", "mini2sf"), "24", "PSF container, version 0x24"),
        psf("gsf", "Game Boy Advance sound format", listOf("gsf", "minigsf"), "22", "PSF container, version 0x22"),
        psf("ncsf", "Nitro Composer sound format", listOf("ncsf", "minincsf"), "25", "PSF container, version 0x25"),
        psf("ssf", "Saturn sound format", listOf("ssf", "minissf"), "11", "PSF container, version 0x11"),
        a("rsn", "RAR-packed SPC set", desc = "RAR archive of SPC files"),
        a("tfmx", "TFMX module", listOf("tfmx", "mdat"), listOf(sig(0, "TFMX"))),
        a("sc68", "SC68 Atari ST/Amiga music", sigs = listOf(sig(0, "SC68 Music-file"))),
        // Sound file formats
        a("avr", "Audio Visual Research sound", sigs = listOf(sig(0, "2BIT"))),
        a("ircam", "IRCAM sound file", listOf("ircam", "sf"), listOf(
            sigHex(0, "64 A3 01 00"), sigHex(0, "64 A3 02 00"), sigHex(0, "64 A3 03 00"), sigHex(0, "64 A3 04 00"),
            sigHex(0, "00 01 A3 64"), sigHex(0, "00 02 A3 64"), sigHex(0, "00 03 A3 64"), sigHex(0, "00 04 A3 64"))),
        a("nist", "NIST SPHERE sound", listOf("nist", "sph"), listOf(sig(0, "NIST_1A"))),
        a("smp", "Sample Vision sample"),
        a("syx", "MIDI SysEx dump", desc = "Raw MIDI system exclusive data"),
        a("tbt", "TabIt tablature", cat = FormatCategory.MUSIC_NOTATION),
        a("cwt", "Cakewalk template"),
        a("bun", "Cakewalk bundle"),
        a("nwctxt", "NoteWorthy Composer text", cat = FormatCategory.MUSIC_NOTATION,
            sigs = listOf(sig(0, "!NoteWorthyComposer")), text = true),
    )
}
