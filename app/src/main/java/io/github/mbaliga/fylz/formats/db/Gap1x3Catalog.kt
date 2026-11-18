package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*

/** Legacy 8/16-bit disk, tape and cartridge images, and console save/memory-card formats. */
object Gap1x3Catalog {

    private fun f(
        id: String, label: String, cat: FormatCategory, ext: List<String> = listOf(id),
        mime: List<String> = emptyList(), sigs: List<Signature> = emptyList(),
        container: String? = null, text: Boolean = false, d: String = "",
    ) = FormatSpec(id, label, cat, ext, mime, sigs, container, text, d)

    private val D = FormatCategory.DISK_IMAGE
    private val R = FormatCategory.ROM
    private val G = FormatCategory.GAME

    val specs: List<FormatSpec> = listOf(
        // ---- Commodore ----
        f("t64", "C64 tape container (T64)", D, sigs = listOf(
            sig(0, "C64S tape image file"), sig(0, "C64 tape image file")),
            d = "Emulator container holding C64 program files from tape."),
        f("d71", "Commodore 1571 disk image", D, d = "Double-sided 1541-compatible disk image."),
        f("d81", "Commodore 1581 disk image", D, d = "3.5-inch 800 KB Commodore disk image."),
        f("d80", "Commodore 8050 disk image", D),
        f("d82", "Commodore 8250 disk image", D),
        f("prg", "C64 program", R, d = "Commodore program file: 2-byte load address followed by data."),
        f("p00", "C64 PC64 program file", R, listOf("p00", "s00", "u00", "r00"),
            sigs = listOf(sig(0, "C64File") + sigHex(7, "00")),
            d = "PC64 container wrapping one Commodore file."),
        f("vsf", "VICE snapshot", D, sigs = listOf(sig(0, "VICE Snapshot File\u001A")),
            d = "Emulator machine-state snapshot."),
        // ---- Sinclair / TR-DOS ----
        f("trd", "TR-DOS disk image", D, d = "Beta Disk / TR-DOS disk image used on ZX Spectrum clones."),
        f("scl", "TR-DOS SCL disk archive", D, sigs = listOf(sig(0, "SINCLAIR")),
            d = "Compact file container for TR-DOS disks."),
        // ---- Atari 8-bit ----
        f("atr", "Atari 8-bit disk image", D, d = "Disk image with a 16-byte header, as used by Atari 8-bit emulators."),
        f("xfd", "Atari 8-bit raw disk image", D, d = "Headerless sector dump."),
        f("xex-atari", "Atari 8-bit executable", R, listOf("xex-atari", "obx"),
            d = "Atari DOS binary load file (id qualified: .xex is also Xbox 360)."),
        f("cas", "Atari/MSX cassette image", D, sigs = listOf(
            sig(0, "FUJI"), sigHex(0, "1F A6 DE BA CC 13 7D 74")),
            d = "Tape image: FUJI chunk format (Atari) or MSX cassette header."),
        // ---- Amstrad / Spectrum / BBC ----
        f("dsk", "CPC/Spectrum DSK disk image", D, sigs = listOf(
            sig(0, "EXTENDED CPC DSK File"), sig(0, "MV - CPCEMU Disk-File")),
            d = "Standard or extended CPCEMU disk image (Amstrad CPC, ZX Spectrum +3)."),
        f("ssd", "BBC Micro single-sided disk image", D),
        f("dsd", "BBC Micro double-sided disk image", D),
        // ---- Apple II / generic floppy ----
        f("woz", "Apple II WOZ disk image", D, sigs = listOf(
            sigHex(0, "57 4F 5A 31 FF 0A 0D 0A"), sigHex(0, "57 4F 5A 32 FF 0A 0D 0A")),
            d = "Bit-accurate Apple II floppy image."),
        f("2mg", "Apple II 2IMG disk image", D, listOf("2mg", "2img"), sigs = listOf(sig(0, "2IMG"))),
        f("a2r", "Applesauce flux image", D, sigs = listOf(
            sigHex(0, "41 32 52 32 FF 0A 0D 0A"), sigHex(0, "41 32 52 33 FF 0A 0D 0A")),
            d = "Raw flux capture of Apple II disks."),
        f("hfe", "HxC floppy emulator image", D, sigs = listOf(
            sig(0, "HXCPICFE"), sig(0, "HXCHFEV3"))),
        f("fdi", "Formatted Disk Image (FDI)", D, sigs = listOf(sig(0, "FDI")),
            d = "Floppy image with track descriptors."),
        f("nfd", "T98-Next NFD floppy image", D, sigs = listOf(
            sig(0, "T98FDDIMAGE.R0\u0000"), sig(0, "T98FDDIMAGE.R1\u0000"))),
        f("dmk", "TRS-80 DMK floppy image", D),
        f("td0", "Teledisk floppy image", D),
        f("imd", "ImageDisk floppy image", D, sigs = listOf(sig(0, "IMD ")),
            d = "ImageDisk image with ASCII header."),
        f("udi", "Ultra Disk Image", D, sigs = listOf(sig(0, "UDI!")),
            d = "Floppy image format used by ZX Spectrum emulators."),
        // ---- Console saves and memory cards ----
        f("sl2", "Dark Souls save", G, d = "Encrypted save file for Dark Souls games."),
        f("gci", "GameCube memory card save", G, d = "Single save exported from a GameCube memory card."),
        f("vmu", "Dreamcast VMU image", G, listOf("vmu"),
            d = "Raw dump of a Dreamcast Visual Memory Unit."),
        f("vms", "Dreamcast VMS save", G, d = "Single Dreamcast save file."),
        f("ps2", "PS2 memory card image", G, listOf("ps2", "mc2"),
            sigs = listOf(sig(0, "Sony PS2 Memory Card Format ")),
            d = "Raw PlayStation 2 memory card image."),
        f("ps3psv", "PS3 save (PSV)", G, listOf("psv"), sigs = listOf(sigHex(0, "00 56 53 50")),
            d = "PlayStation save exported with a VSP header."),
        f("ps1mcr", "PS1 memory card image", G, listOf("mcr", "mcd"),
            d = "Raw PlayStation 1 memory card dump."),
        f("vec", "Vectrex ROM", R, listOf("vec"), d = "Cartridge ROM dump for the GCE Vectrex."),
    )
}
