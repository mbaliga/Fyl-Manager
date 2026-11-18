package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*

/** Legacy disk/tape images, console ROMs, Switch/Wii U/PS3/PSP containers, ROM patches, emulator movies. */
object Gap2x6Catalog {

    private fun f(
        id: String, label: String, cat: FormatCategory, ext: List<String> = listOf(id),
        mime: List<String> = emptyList(), sigs: List<Signature> = emptyList(),
        container: String? = null, text: Boolean = false, d: String = "",
    ) = FormatSpec(id, label, cat, ext, mime, sigs, container, text, d)

    private val DISK = FormatCategory.DISK_IMAGE
    private val ROM = FormatCategory.ROM
    private val GAME = FormatCategory.GAME

    val specs: List<FormatSpec> = listOf(
        // ---- Legacy disk / tape images ----
        f("mgt", "DISCiPLE/+D disk image", DISK, d = "ZX Spectrum DISCiPLE / Plus D raw disk image."),
        f("uef", "Acorn UEF tape image", DISK,
            sigs = listOf(sig(0, "UEF File!") + sigHex(9, "00")),
            d = "Unified Emulator Format tape image for Acorn/BBC machines; often gzip-compressed."),
        f("shk", "Apple II ShrinkIt archive", FormatCategory.ARCHIVE, listOf("shk", "sdk", "bxy", "bny", "bqy"),
            sigs = listOf(sigHex(0, "4E F5 46 E9 6C E5")),
            d = "NuFX archive; .sdk is a ShrinkIt disk image, .bxy a Binary II wrapped archive."),
        f("nbz", "C64 disk image (X64/NBZ)", DISK, listOf("nbz", "x64"),
            sigs = listOf(sigHex(0, "43 15 41 64")),
            d = "Commodore 64 disk images: X64 (VICE header) and NBZ."),
        f("p64", "C64 P64 flux disk image", DISK,
            sigs = listOf(sig(0, "P64-1541")),
            d = "Commodore 1541 flux-level disk image."),
        f("mfm", "HxC MFM disk image", DISK,
            sigs = listOf(sig(0, "HXCMFM")),
            d = "HxC Floppy Emulator MFM bitstream disk image."),
        f("dfi", "DiscFerret disk image", DISK,
            sigs = listOf(sig(0, "DFER")),
            d = "DiscFerret flux disk image."),
        f("cqm", "CopyQM disk image", DISK, d = "CopyQM floppy disk image."),
        f("d67", "Commodore drive disk image", DISK, listOf("d67", "d90"),
            d = "Commodore 2040/3040 (D67) and D9060/D9090 (D90) drive disk images."),
        f("d2m", "CMD disk image", DISK, listOf("d2m", "d4m", "dnp", "dhd"),
            d = "CMD FD2000/FD4000 disk images, native partitions and hard disk images."),
        f("adz", "Amiga compressed ADF", DISK, d = "Gzip-compressed Amiga Disk File."),
        f("info", "Amiga icon", FormatCategory.ICON,
            sigs = listOf(sigHex(0, "E3 10 00 01")),
            d = "Amiga Workbench .info icon file."),
        f("stx", "Atari ST disk image", DISK, listOf("stx", "dim"),
            sigs = listOf(sigHex(0, "52 53 59 00")),
            d = "Pasti STX (RSY header) and related Atari ST disk images."),
        f("atx", "Atari VAPI disk image", DISK,
            sigs = listOf(sig(0, "AT8X")),
            d = "Atari 8-bit VAPI protected-disk image."),
        f("d88", "PC-98 disk image", DISK, listOf("d88", "d98", "hdi", "nhd", "fdd", "hdm"),
            d = "NEC PC-98 / PC-88 floppy and hard disk images."),
        f("xdf-x68000", "Sharp X68000 disk image", DISK, listOf("xdf")),
        f("jv3", "TRS-80 disk image", DISK, listOf("jv1", "jv3"),
            d = "TRS-80 JV1 / JV3 floppy disk images."),
        f("vdk", "Dragon/CoCo disk image", DISK),
        f("ort", "Oric tape image", DISK),
        f("szx", "ZX Spectrum snapshot or tape", GAME, listOf("szx", "pzx", "rzx"),
            sigs = listOf(sig(0, "ZXST"), sig(0, "PZXT"), sig(0, "RZX!")),
            d = "SZX snapshot, PZX tape and RZX input recording."),

        // ---- Console ROMs ----
        f("bs", "Satellaview BS-X ROM", ROM),
        f("o2", "Magnavox Odyssey 2 ROM", ROM),
        f("chf", "Fairchild Channel F ROM", ROM),
        f("itv", "Intellivision ROM", ROM),
        f("qd", "Famicom Disk System QD image", DISK, d = "Quick Disk image."),
        f("ndd", "Nintendo 64DD disk", ROM),

        // ---- Switch / Wii U / GameCube / PS3 / PSP ----
        f("nsz", "Nintendo Switch compressed package", GAME, d = "Compressed NSP (PFS0) title package."),
        f("rpx", "Wii U executable / archive", GAME, listOf("rpx", "wua"),
            d = "RPX Wii U executable; WUA Cemu title archive."),
        f("tgc", "GameCube mini disc", GAME),
        f("pup", "PS3 update package", FormatCategory.FIRMWARE,
            sigs = listOf(sigHex(0, "53 43 45 55 46 00 00 00")),
            d = "PlayStation 3 system software update."),
        f("sfo", "PlayStation param.sfo", GAME,
            sigs = listOf(sigHex(0, "00 50 53 46")),
            d = "System File Object holding title metadata."),
        f("edat", "PS3 protected data", GAME, listOf("edat", "sdat"),
            sigs = listOf(sigHex(0, "4E 50 44 00")),
            d = "NPD-wrapped EDAT/SDAT data."),
        f("vmp", "PSP/PS3 memory card", GAME, listOf("vmp", "vm1", "vm2"),
            sigs = listOf(sigHex(0, "00 50 4D 56")),
            d = "PS1 memory card saves in PSP/PS3 format."),
        f("gme", "DexDrive memory card", GAME,
            sigs = listOf(sig(0, "123-456-STD")),
            d = "PlayStation 1 memory card dump from DexDrive."),
        f("toc", "CDRDAO table of contents", DISK, text = true,
            d = "cdrdao CD layout text file."),
        f("uif", "MagicISO image", DISK),
        f("ecm", "ECM error-code-modeler", FormatCategory.COMPRESSION,
            sigs = listOf(sigHex(0, "45 43 4D 00")),
            d = "Compressed disc image with removable ECC/EDC data."),
        f("zso", "Compressed PSP ISO", DISK, listOf("zso", "dax", "jso"),
            sigs = listOf(sig(0, "ZISO"), sig(0, "JISO"), sigHex(0, "44 41 58 00")),
            d = "CSO-family compressed ISO: ZSO, DAX, JSO."),

        // ---- ROM patches, saves, TAS movies ----
        f("ppf", "ROM / disc patch", GAME, listOf("ppf", "xdelta", "rup", "aps"),
            sigs = listOf(sig(0, "PPF3"), sig(0, "PPF2"), sig(0, "PPF1"),
                sigHex(0, "D6 C3 C4 00"), sig(0, "NINJA2"), sig(0, "APS1")),
            d = "PPF, xdelta (VCDIFF), NINJA RUP and APS patch formats."),
        f("dsv", "Emulator save", GAME, listOf("dsv"),
            d = "DeSmuME save data."),
        f("vbm", "Emulator TAS movie", GAME, listOf("vbm", "fm2", "smv", "m64", "lsmv", "gmv", "fmv", "mmv", "fm3", "ltm"),
            sigs = listOf(sigHex(0, "56 42 4D 1A"), sigHex(0, "4D 36 34 1A")),
            d = "Tool-assisted input recordings from many emulators."),
        f("dtm", "Dolphin TAS movie", GAME,
            sigs = listOf(sigHex(0, "44 54 4D 1A")),
            d = "Dolphin emulator input recording."),
    )
}
