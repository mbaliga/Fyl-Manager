package io.github.mbaliga.fylz.core.format

import io.github.mbaliga.fylz.core.model.EntryKind
import java.util.Locale

enum class PreviewFamily {
    DIRECTORY,
    MARKDOWN,
    TEXT,
    IMAGE,
    PDF,
    AUDIO,
    VIDEO,
    ARCHIVE,
    FONT,
    OFFICE,
    EBOOK,
    MODEL_3D,
    CAD_2D,
    DATABASE,
    GEOSPATIAL,
    SCIENTIFIC,
    EXECUTABLE,
    PACKAGE,
    BINARY,
    DESIGN,
}

enum class PreviewDepth {
    RENDERED,
    STRUCTURED,
    INSPECTED,
}

data class FileFormatDescriptor(
    val family: PreviewFamily,
    val depth: PreviewDepth,
    val label: String,
    val extension: String,
    val rendererId: String? = null,
    val notes: String? = null,
) {
    val rendered: Boolean get() = depth == PreviewDepth.RENDERED
}

/**
 * Central, deterministic preview routing table.
 *
 * Every non-directory file resolves to a descriptor. Formats without a safe built-in semantic
 * renderer still receive the bounded universal inspector instead of a dead-end "unsupported"
 * screen. This is intentionally extension-and-MIME driven; individual renderers must still verify
 * signatures and bounds before parsing untrusted input.
 *
 * Relocated here (WP-1.3) from `io.github.mbaliga.fylz.preview` — it was already pure Kotlin
 * with no Android dependency, so this move is a package change only, not a rewrite. The
 * Android-coupled parts of the old `preview` package (route-to-composable dispatch, the
 * `ContentResolver`-backed universal inspector) stay in `app`, importing this module.
 */
object FileFormatRegistry {
    private val markdown = setOf("md", "markdown", "mdown", "mkd", "mdx")
    private val text = setOf(
        "txt", "text", "log", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml",
        "toml", "ini", "conf", "cfg", "properties", "gradle", "kts", "kt", "java", "py",
        "js", "mjs", "cjs", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "sass",
        "less", "sql", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "c", "h", "cc",
        "cpp", "cxx", "hpp", "rs", "go", "rb", "php", "swift", "dart", "lua", "r", "jl",
        "diff", "patch", "prompt", "instructions", "agent", "mmd", "mermaid", "env", "gitignore",
        "gitattributes", "dockerfile", "makefile", "cmake", "ninja", "srt", "vtt", "ass", "ssa",
        "ics", "vcf", "bib", "tex", "rst", "adoc", "graphql", "proto", "smali",
    )
    private val images = setOf(
        "jpg", "jpeg", "jpe", "png", "gif", "webp", "avif", "heic", "heif", "bmp", "dib",
        "tif", "tiff", "ico", "cur", "svg", "svgz", "jp2", "j2k", "jxl", "psd", "xcf", "kra",
        "raw", "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2",
    )
    private val audio = setOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "wave", "flac", "alac", "amr", "mid",
        "midi", "aiff", "aif", "wma", "ape", "mka",
    )
    private val video = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "3gp", "3g2", "avi", "mpeg", "mpg", "mpe", "ts",
        "mts", "m2ts", "vob", "ogv", "wmv", "flv",
    )
    private val archives = setOf(
        "zip", "zipx", "7z", "rar", "tar", "gz", "gzip", "bz2", "xz", "zst", "tgz", "tbz", "tbz2",
        "txz", "cab", "arj", "lha", "lzh", "cpio", "iso", "img", "dmg", "wim", "xar", "deb", "rpm",
        // Compound forms of the above -- compoundExtension() below folds "backup.tar.gz" to the
        // literal token "tar.gz" rather than the bare "gz" a plain substringAfterLast would give,
        // so the compound has to be listed here too or a compressed tarball falls all the way
        // through to the BINARY catch-all instead of ARCHIVE.
        "tar.gz", "tar.bz2", "tar.xz", "tar.zst",
    )
    private val design = setOf("fig", "jam")
    private val fonts = setOf("ttf", "otf", "ttc", "otc", "woff", "woff2", "eot", "pfb", "pfm", "bdf", "pcf")
    private val office = setOf(
        "doc", "docx", "docm", "dot", "dotx", "odt", "ott", "rtf", "pages", "wpd",
        "xls", "xlsx", "xlsm", "xlsb", "ods", "ots", "numbers", "csv",
        "ppt", "pptx", "pptm", "pps", "ppsx", "odp", "otp", "key",
        "vsd", "vsdx", "odg", "pub", "one",
    )
    private val ebooks = setOf("epub", "mobi", "azw", "azw3", "kf8", "fb2", "cbz", "cbr", "djvu", "djv")
    private val models3d = setOf(
        "obj", "stl", "ply", "off", "gltf", "glb", "dae", "fbx", "3ds", "3mf", "blend", "ase",
        "lwo", "lws", "x", "bvh", "md2", "md3", "md5mesh", "md5anim", "ms3d", "ac", "cob",
        "scn", "usd", "usda", "usdc", "usdz", "wrl", "vrml", "x3d", "ifc", "step", "stp", "iges",
        "igs", "brep", "sat", "sab",
    )
    private val cad2d = setOf(
        "dxf", "dwg", "dws", "dwt", "dxb", "svg", "svgz", "hpgl", "hpg", "plt", "cgm", "emf", "wmf",
        "gbr", "ger", "gtl", "gbl", "gts", "gbs", "gto", "gbo", "drl", "excellon", "sch", "brd",
        "kicad_sch", "kicad_pcb", "kicad_mod", "kicad_sym", "dsn", "pcbdoc", "schdoc",
    )
    private val databases = setOf(
        "sqlite", "sqlite3", "db", "db3", "sdb", "mdb", "accdb", "realm", "leveldb", "duckdb", "parquet",
        "avro", "orc", "dbf", "fdb", "gdb", "pdb",
    )
    private val geospatial = setOf(
        "geojson", "topojson", "kml", "kmz", "gpx", "shp", "shx", "prj", "qpj", "gpkg", "mbtiles",
        "tif", "tiff", "geotiff", "asc", "dem", "las", "laz", "osm", "pbf",
    )
    private val scientific = setOf(
        "fits", "fit", "fts", "dicom", "dcm", "nii", "nii.gz", "nrrd", "mha", "mhd", "h5", "hdf5",
        "hdf", "nc", "cdf", "mat", "sav", "edf", "bdf", "mzml", "mzxml", "pdb", "cif", "mmcif",
    )
    private val executables = setOf(
        "exe", "dll", "sys", "com", "msi", "elf", "so", "dylib", "bin", "appimage", "dex", "odex", "vdex",
        "class", "jar", "wasm", "ko", "o", "a", "lib", "apk", "aab", "ipa",
    )
    private val packages = setOf(
        "apk", "aab", "apks", "xapk", "apkm", "ipa", "msix", "appx", "deb", "rpm", "flatpak", "snap",
        "jar", "war", "ear", "whl", "gem", "nupkg", "crate", "vsix",
    )

    fun describe(name: String, mimeType: String, kind: EntryKind? = null): FileFormatDescriptor {
        if (kind == EntryKind.DIRECTORY) {
            return FileFormatDescriptor(PreviewFamily.DIRECTORY, PreviewDepth.STRUCTURED, "Folder", "")
        }
        val normalizedName = name.lowercase(Locale.ROOT)
        val extension = compoundExtension(normalizedName)
        val mime = mimeType.lowercase(Locale.ROOT)

        return when {
            extension in markdown || kind == EntryKind.MARKDOWN -> descriptor(PreviewFamily.MARKDOWN, PreviewDepth.RENDERED, "Markdown", extension, "markdown")
            extension == "pdf" || mime == "application/pdf" || kind == EntryKind.PDF -> descriptor(PreviewFamily.PDF, PreviewDepth.RENDERED, "PDF document", extension, "pdf")
            extension in cad2d -> descriptor(
                PreviewFamily.CAD_2D,
                if (extension in setOf("dxf", "svg", "svgz", "hpgl", "hpg", "plt", "gbr", "ger")) PreviewDepth.RENDERED else PreviewDepth.INSPECTED,
                cadLabel(extension),
                extension,
                if (extension == "dxf") "dxf" else if (extension in setOf("svg", "svgz")) "image" else null,
                if (extension == "dwg") "DWG is proprietary; Fylz exposes signatures, metadata and extractable text when a native decoder is unavailable." else null,
            )
            extension in models3d || mime.startsWith("model/") -> descriptor(
                PreviewFamily.MODEL_3D,
                if (extension in setOf("obj", "stl", "ply", "off", "gltf", "glb")) PreviewDepth.RENDERED else PreviewDepth.STRUCTURED,
                modelLabel(extension),
                extension,
                if (extension in setOf("obj", "stl", "ply", "off")) "mesh-wireframe" else if (extension in setOf("gltf", "glb")) "gltf" else null,
            )
            extension in images || mime.startsWith("image/") || kind == EntryKind.IMAGE -> descriptor(PreviewFamily.IMAGE, PreviewDepth.RENDERED, "Image", extension, "image")
            extension in audio || mime.startsWith("audio/") || kind == EntryKind.AUDIO -> descriptor(PreviewFamily.AUDIO, PreviewDepth.RENDERED, "Audio", extension, "media")
            extension in video || mime.startsWith("video/") || kind == EntryKind.VIDEO -> descriptor(PreviewFamily.VIDEO, PreviewDepth.RENDERED, "Video", extension, "media")
            extension in archives || kind == EntryKind.ARCHIVE || isArchiveMime(mime) -> descriptor(
                PreviewFamily.ARCHIVE,
                PreviewDepth.STRUCTURED,
                "Archive or disk image",
                extension,
                "archive",
                if (extension == "rar") "RAR listing is not supported." else null,
            )
            extension in design -> descriptor(PreviewFamily.DESIGN, PreviewDepth.STRUCTURED, "Design document", extension, "design")
            extension in fonts || mime.startsWith("font/") -> descriptor(PreviewFamily.FONT, PreviewDepth.RENDERED, "Font", extension, "font")
            extension in office || isOfficeMime(mime) -> descriptor(PreviewFamily.OFFICE, PreviewDepth.STRUCTURED, "Office document", extension, "office")
            extension in ebooks -> descriptor(PreviewFamily.EBOOK, PreviewDepth.STRUCTURED, "E-book or comic", extension, "ebook")
            extension in geospatial -> descriptor(PreviewFamily.GEOSPATIAL, PreviewDepth.STRUCTURED, "Geospatial data", extension, "geospatial")
            extension in scientific -> descriptor(PreviewFamily.SCIENTIFIC, PreviewDepth.STRUCTURED, "Scientific or medical data", extension, "scientific")
            extension in databases || mime.contains("sqlite") -> descriptor(PreviewFamily.DATABASE, PreviewDepth.STRUCTURED, "Database or columnar data", extension, "database")
            extension in packages -> descriptor(PreviewFamily.PACKAGE, PreviewDepth.STRUCTURED, "Software package", extension, "package")
            extension in executables || isExecutableMime(mime) -> descriptor(PreviewFamily.EXECUTABLE, PreviewDepth.INSPECTED, "Executable or binary library", extension, "binary")
            extension in text || mime.startsWith("text/") || kind == EntryKind.TEXT || isTextMime(mime) -> descriptor(PreviewFamily.TEXT, PreviewDepth.RENDERED, "Text or source", extension, "text")
            else -> descriptor(PreviewFamily.BINARY, PreviewDepth.INSPECTED, "Binary or unrecognized format", extension, "binary")
        }
    }

    fun compoundExtension(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val compound = listOf("nii.gz", "tar.gz", "tar.bz2", "tar.xz", "tar.zst", "user.js")
            .firstOrNull { lower.endsWith(".$it") }
        return compound ?: lower.substringAfterLast('.', "")
    }

    private fun descriptor(
        family: PreviewFamily,
        depth: PreviewDepth,
        label: String,
        extension: String,
        rendererId: String? = null,
        notes: String? = null,
    ) = FileFormatDescriptor(family, depth, label, extension, rendererId, notes)

    private fun isArchiveMime(value: String) = value in setOf(
        "application/zip", "application/x-7z-compressed", "application/vnd.rar", "application/x-rar-compressed",
        "application/x-tar", "application/gzip", "application/x-bzip2", "application/x-xz", "application/zstd",
    )

    private fun isOfficeMime(value: String) = value.startsWith("application/vnd.openxmlformats-officedocument") ||
        value.startsWith("application/vnd.oasis.opendocument") || value == "application/msword" ||
        value.startsWith("application/vnd.ms-") || value == "application/rtf"

    private fun isTextMime(value: String) = value in setOf(
        "application/json", "application/ld+json", "application/xml", "application/yaml", "application/toml",
        "application/javascript", "application/sql", "application/graphql",
    )

    private fun isExecutableMime(value: String) = value in setOf(
        "application/vnd.android.package-archive", "application/x-executable", "application/x-sharedlib",
        "application/x-msdownload", "application/wasm", "application/java-archive",
    )

    private fun cadLabel(extension: String) = when (extension) {
        "dxf" -> "DXF CAD drawing"
        "dwg", "dws", "dwt" -> "DWG-family CAD drawing"
        "gbr", "ger", "gtl", "gbl", "gts", "gbs", "gto", "gbo" -> "Gerber PCB artwork"
        "kicad_sch", "sch", "schdoc" -> "Electronic schematic"
        "kicad_pcb", "brd", "pcbdoc" -> "PCB layout"
        else -> "CAD or technical drawing"
    }

    private fun modelLabel(extension: String) = when (extension) {
        "obj" -> "Wavefront OBJ model"
        "stl" -> "STL mesh"
        "ply" -> "PLY polygon model"
        "off" -> "OFF polygon model"
        "gltf", "glb" -> "glTF scene"
        "ifc" -> "IFC building model"
        "step", "stp" -> "STEP CAD model"
        "iges", "igs" -> "IGES CAD model"
        else -> "3D model or scene"
    }
}
