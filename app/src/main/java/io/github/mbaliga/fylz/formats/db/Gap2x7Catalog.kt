package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*

/** Game-engine archives, mod tooling and RPG Maker/GameMaker/Unreal/Roblox/Nintendo/Xbox asset formats. */
object Gap2x7Catalog {

    private fun f(
        id: String, label: String, ext: List<String> = listOf(id),
        mime: List<String> = emptyList(), sigs: List<Signature> = emptyList(),
        container: String? = null, text: Boolean = false, d: String = "",
    ) = FormatSpec(id, label, FormatCategory.GAME, ext, mime, sigs, container, text, d)

    private val bin = listOf("application/octet-stream")

    val specs: List<FormatSpec> = listOf(
        // ---- Bohemia (Arma / DayZ) ----
        f("pbo", "Arma/DayZ PBO archive", listOf("pbo", "ebo"), bin,
            listOf(sigHex(0, "00 73 72 65 56")),
            d = "Bohemia Interactive packed object; begins with an empty-name 'Vers' header entry."),
        f("paa", "Arma texture/model", listOf("paa", "pac", "rvmat", "p3d", "wrp"), bin,
            d = "Arma/OFP textures (paa, pac), materials (rvmat), models (p3d) and worlds (wrp)."),
        // ---- Rockstar ----
        f("txd", "GTA RenderWare asset", listOf("txd", "ifp", "gxt"), bin,
            d = "GTA III/VC/SA texture dictionary (txd), animation (ifp) and text table (gxt)."),
        f("ydr", "GTA V resource", listOf("ydr", "yft", "ydd", "ybn", "ymap", "ytyp", "ymt", "ytd"), bin,
            listOf(sig(0, "RSC7")),
            d = "GTA V RAGE resource files (models, fragments, collisions, maps, textures)."),
        // ---- Bethesda ----
        f("hkx", "Bethesda game asset", listOf("hkx", "kf", "bto", "lip", "fuz", "xwm"), bin,
            listOf(sigHex(0, "57 E0 E0 57 10 C0 C0 10")),
            d = "Havok animation/behaviour (hkx), NIF animation (kf), terrain, lip sync, voice and XWM audio."),
        f("dlstrings", "Bethesda strings and materials",
            listOf("dlstrings", "ilstrings", "bgsm", "bgem", "egm", "egt", "tri"), bin,
            d = "Skyrim/Fallout localized string tables, materials and face morph data."),
        // ---- RPG Maker ----
        f("rxdata", "RPG Maker data", listOf("rxdata", "rvdata", "rvdata2", "rgss3a", "rgssad", "rgss2a"), bin,
            listOf(sig(0, "RGSSAD") + sigHex(6, "00")),
            d = "Ruby Marshal data (XP/VX/VX Ace) and RGSS encrypted archives."),
        f("rpgsave", "RPG Maker MV save", listOf("rpgsave", "rmmzsave", "rpgmvm", "rpgmvw"), bin,
            d = "RPG Maker MV/MZ save files and encrypted assets."),
        f("lmu", "RPG Maker 2000/2003 data", listOf("lmu", "lmt", "lsd"), bin,
            listOf(sigHex(0, "0A") + sig(1, "LcfMapUnit"),
                sigHex(0, "0A") + sig(1, "LcfMapTree"),
                sigHex(0, "0B") + sig(1, "LcfSaveData")),
            d = "Map (lmu), map tree (lmt) and save (lsd) data."),
        // ---- GameMaker / Construct ----
        f("yyp", "GameMaker project", listOf("yyp", "gmk", "gm81", "gmz", "yyz", "yymps"), bin,
            d = "GameMaker Studio/Classic project and package files."),
        f("c3p", "Construct 3 project", listOf("c3p"), bin, d = "Construct 3 project."),
        // ---- Unreal ----
        f("uproject", "Unreal Engine project", listOf("uproject", "uplugin", "ubulk", "uptnl", "usmap"), bin,
            d = "Unreal project/plugin descriptors (JSON), bulk data and mapping files."),
        f("locres", "Unreal localization resource", listOf("locres"), bin,
            listOf(sigHex(0, "0E 14 74 75 67 4A 03 FC 4A 15 90 9D C3 37 7F 1B")),
            d = "Unreal Engine compiled localization (FTextLocalizationResource)."),
        // ---- Roblox / Minecraft ----
        f("rbxlx", "Roblox XML place", listOf("rbxlx", "rbxmx"), listOf("application/xml"),
            listOf(sig(0, "<roblox ")), text = true, d = "Roblox place/model in XML form."),
        f("mcstructure", "Minecraft Bedrock structure", listOf("mcstructure", "mctemplate"), bin,
            d = "Little-endian NBT structure (mcstructure) or world template package."),
        // ---- Blizzard / Diablo ----
        f("w3g", "Warcraft III replay and objects", listOf("w3g", "w3n", "w3z", "w3o", "w3u", "w3t"), bin,
            listOf(sig(0, "Warcraft III recorded game") + sigHex(26, "1A 00")),
            d = "Replay (w3g), campaign/map archives and object data."),
        f("d2s", "Diablo II save", listOf("d2s", "d2i", "d2x", "d2p"), bin,
            listOf(sigHex(0, "55 AA 55 AA")), d = "Diablo II character and stash files."),
        f("sc2map", "StarCraft II map", listOf("sc2map", "sc2mod", "sc2save"), bin,
            listOf(sigHex(0, "4D 50 51 1B")), d = "MPQ-based StarCraft II map, mod and save."),
        // ---- id Tech / Quake / Doom ----
        f("wal", "Quake engine assets", listOf("wal", "sp2", "mdr", "skc", "ska", "aas"), bin,
            d = "Quake II textures/sprites, Raven models and skeletons, Quake III AAS."),
        f("deh", "DeHackEd patch", listOf("deh", "bex"), bin,
            d = "DeHackEd patch (text)."),
        f("ipk3", "ZDoom IPK3 resource pack", listOf("ipk3"), bin, container = "zip",
            d = "ZIP-based ZDoom resource pack."),
        f("pk7", "ZDoom PK7 resource pack", listOf("pk7", "ipk7"), bin, container = "7z",
            d = "7z-based ZDoom resource pack."),
        f("dm2", "Quake demo", listOf("dm2", "dm_68", "dm_73"), bin, d = "Quake II/III network demo recordings."),
        f("bms", "QuickBMS script", listOf("bms"), listOf("text/plain"), text = true,
            d = "Script for the QuickBMS extraction tool."),
        // ---- Nintendo ----
        f("brres", "Nintendo BRRES", listOf("brres"), bin, listOf(sig(0, "bres")),
            d = "Wii resource archive (models, textures, animations)."),
        f("szs", "Nintendo SARC/BFRES", listOf("szs", "sarc", "bfres", "bntx", "bars", "bfstm", "bwav"), bin,
            listOf(sig(0, "Yaz0"), sig(0, "SARC")),
            d = "Yaz0-compressed archive (szs), SARC archive, and Switch/Wii U resources."),
        f("u8", "Nintendo U8/BRSTM", listOf("u8", "brstm", "brsar"), bin,
            listOf(sigHex(0, "55 AA 38 2D"), sig(0, "RSTM"), sig(0, "RSAR")),
            d = "Wii U8 archive, BRSTM streamed audio and BRSAR sound archive."),
        f("bcstm", "Nintendo 3DS assets", listOf("bcstm", "bcwav", "bflim", "bclim", "bcres"), bin,
            listOf(sig(0, "CSTM"), sig(0, "CWAV")),
            d = "3DS streamed audio, wave audio, images and resources."),
        // ---- Xbox ----
        f("xvd", "Xbox virtual drive", listOf("xvd", "xvc", "xcrd"), bin,
            listOf(sig(512, "msft-xvd")), d = "Xbox One/Series virtual disk; magic at offset 0x200."),
        // ---- Audio middleware ----
        f("wproj", "Wwise project", listOf("wproj", "wwu"), listOf("application/xml"), text = true,
            d = "Audiokinetic Wwise XML project and work unit."),
        f("fev", "FMOD Designer events", listOf("fev"), bin, listOf(sig(0, "FEV1"))),
        f("fspro", "FMOD Studio project", listOf("fspro"), listOf("application/xml"), text = true),
    )
}
