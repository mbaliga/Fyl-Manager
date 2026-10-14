//! Content-type detection from raw bytes -- magic-byte and container-structure sniffing, never a
//! full parse. See `docs/agent/MASTER_PLAN.md` section 5, M2.5.
//!
//! ## Coverage
//!
//! The master plan cites freedesktop's `shared-mime-info` magic database ("the top 300
//! formats") as a seed, gated on "MIT/GPL dual? verify". `shared-mime-info` itself is licensed
//! GPL-2.0-or-later (its own `COPYING` file), which `deny.toml` denies for an ordinary Cargo
//! dependency by policy (section 2.2) -- and copying its magic *rules* wholesale would still be
//! copying that project's expression of them, not just the underlying facts. So this hands-write
//! rules from each format's own public specification instead, exactly the plan's own stated
//! fallback. What is covered here is every format the plan names explicitly (ISO9660, UDF, MBR,
//! GPT, DMG, VHD, VHDX, QCOW, VMDK, VDI, WIM, 7z, RAR4/5, ZIP, zstd, xz, bz2, gz, lz4, MPEG-TS,
//! PDF, OOXML, ODF, EPUB, APK) plus the common raster/executable/database formats a file manager
//! needs a fast first signal for. That is nowhere near 300; the table-driven design here (plus
//! [`sniff_zip_container`] for the ZIP-based formats) is meant to make growing toward that a
//! matter of adding rows and fixtures, not restructuring.
//!
//! ## Design
//!
//! [`sniff`] takes a byte slice from the START of a file. A handful of formats put their only
//! reliable signature at the END instead (an optical-disc image's volume descriptors sit deep in
//! the header; a disk image's footer sits at EOF) -- [`sniff_with_tail`] additionally takes the
//! last bytes of the file for those. [`HEADER_LEN`]/[`TAIL_LEN`] are how much a caller needs to
//! read for every format below to have a chance of matching; reading less just means the
//! deep-header and trailer-only formats can't be recognised, not a wrong answer.

/// Bytes needed from the start of a file to recognise every header-anchored format below.
/// ISO9660/UDF volume descriptors are the deepest signature (sector 20, ends at 0x9800), so the
/// header buffer must reach one full sector past that.
pub const HEADER_LEN: usize = 21 * SECTOR_LEN;

/// Bytes needed from the END of a file to recognise every trailer-anchored format below (a DMG
/// UDIF trailer and a VHD footer are each exactly one 512-byte sector).
pub const TAIL_LEN: usize = 512;

const SECTOR_LEN: usize = 2048;

/// A recognised format. Variants group roughly as: general-purpose archives/compressors,
/// documents, executables/databases, raster images, disk/optical images, and ZIP-based
/// container formats identified by their first entry (see [`sniff_zip_container`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum Format {
    Zip,
    SevenZip,
    Rar4,
    Rar5,
    Gzip,
    Bzip2,
    Xz,
    Zstd,
    Lz4,
    Pdf,
    Elf,
    Pe,
    MachO,
    MachOFat,
    JavaClass,
    Sqlite,
    Png,
    Jpeg,
    Gif,
    Bmp,
    WebP,
    Tiff,
    Iso9660,
    Udf,
    Mbr,
    Gpt,
    Dmg,
    Vhd,
    Vhdx,
    Qcow,
    Vmdk,
    Vdi,
    Wim,
    MpegTs,
    Apk,
    Jar,
    Epub,
    Ooxml,
    Odf,
}

impl Format {
    /// A short human-readable name, for a placeholder or a debug log -- not a MIME type; see
    /// [`Format::mime`] for the subset that has one worth showing.
    pub fn label(self) -> &'static str {
        match self {
            Format::Zip => "ZIP archive",
            Format::SevenZip => "7-Zip archive",
            Format::Rar4 => "RAR archive (v4)",
            Format::Rar5 => "RAR archive (v5)",
            Format::Gzip => "gzip archive",
            Format::Bzip2 => "bzip2 archive",
            Format::Xz => "xz archive",
            Format::Zstd => "Zstandard archive",
            Format::Lz4 => "LZ4 archive",
            Format::Pdf => "PDF document",
            Format::Elf => "ELF executable",
            Format::Pe => "Windows PE executable",
            Format::MachO => "Mach-O executable",
            Format::MachOFat => "Mach-O universal binary",
            Format::JavaClass => "Java class file",
            Format::Sqlite => "SQLite database",
            Format::Png => "PNG image",
            Format::Jpeg => "JPEG image",
            Format::Gif => "GIF image",
            Format::Bmp => "BMP image",
            Format::WebP => "WebP image",
            Format::Tiff => "TIFF image",
            Format::Iso9660 => "ISO 9660 disc image",
            Format::Udf => "UDF disc image",
            Format::Mbr => "MBR disk image",
            Format::Gpt => "GPT disk image",
            Format::Dmg => "Apple disk image",
            Format::Vhd => "VHD disk image",
            Format::Vhdx => "VHDX disk image",
            Format::Qcow => "QCOW disk image",
            Format::Vmdk => "VMDK disk image",
            Format::Vdi => "VirtualBox disk image",
            Format::Wim => "Windows imaging (WIM) archive",
            Format::MpegTs => "MPEG transport stream",
            Format::Apk => "Android package (APK)",
            Format::Jar => "Java archive (JAR)",
            Format::Epub => "EPUB ebook",
            Format::Ooxml => "Office Open XML document",
            Format::Odf => "OpenDocument file",
        }
    }

    /// The registered IANA media type, for the formats that have one worth asserting. `None`
    /// rather than a guess for the disk/optical-image formats: none of MBR/GPT/VHD/VHDX/QCOW/
    /// VMDK/VDI/WIM has an IANA-registered type, and inventing one would claim a standard that
    /// does not exist. [`Format::Ooxml`] is similarly `None`: telling a Word document apart from
    /// an Excel one needs a deeper look at the ZIP than the first entry's name gives.
    pub fn mime(self) -> Option<&'static str> {
        match self {
            Format::Zip => Some("application/zip"),
            Format::SevenZip => Some("application/x-7z-compressed"),
            Format::Rar4 | Format::Rar5 => Some("application/vnd.rar"),
            Format::Gzip => Some("application/gzip"),
            Format::Bzip2 => Some("application/x-bzip2"),
            Format::Xz => Some("application/x-xz"),
            Format::Zstd => Some("application/zstd"),
            Format::Lz4 => Some("application/x-lz4"),
            Format::Pdf => Some("application/pdf"),
            Format::Elf => Some("application/x-elf"),
            Format::Pe => Some("application/x-msdownload"),
            Format::MachO | Format::MachOFat => Some("application/x-mach-binary"),
            Format::JavaClass => Some("application/java-vm"),
            Format::Sqlite => Some("application/vnd.sqlite3"),
            Format::Png => Some("image/png"),
            Format::Jpeg => Some("image/jpeg"),
            Format::Gif => Some("image/gif"),
            Format::Bmp => Some("image/bmp"),
            Format::WebP => Some("image/webp"),
            Format::Tiff => Some("image/tiff"),
            Format::Iso9660 | Format::Udf => Some("application/x-iso9660-image"),
            Format::Dmg => Some("application/x-apple-diskimage"),
            Format::MpegTs => Some("video/mp2t"),
            Format::Apk => Some("application/vnd.android.package-archive"),
            Format::Jar => Some("application/java-archive"),
            Format::Epub => Some("application/epub+zip"),
            Format::Odf => Some("application/vnd.oasis.opendocument.generic"),
            Format::Mbr
            | Format::Gpt
            | Format::Vhd
            | Format::Vhdx
            | Format::Qcow
            | Format::Vmdk
            | Format::Vdi
            | Format::Wim
            | Format::Ooxml => None,
        }
    }
}

/// Sniff a format from the start of a file alone. Equivalent to `sniff_with_tail(header, None)`
/// -- see that function's doc for the formats this alone cannot recognise.
pub fn sniff(header: &[u8]) -> Option<Format> {
    sniff_with_tail(header, None)
}

/// Sniff a format from a file's header and, when available, its trailing [`TAIL_LEN`] bytes.
///
/// `tail` is `None` when the caller could not or did not read one (a file shorter than
/// [`TAIL_LEN`], or a stream that cannot seek): every header-anchored format is still detected,
/// only [`Format::Dmg`] and a trailing-only [`Format::Vhd`] footer are missed, never guessed at.
pub fn sniff_with_tail(header: &[u8], tail: Option<&[u8]>) -> Option<Format> {
    // GPT before MBR: a GPT disk still carries a protective MBR (0x55AA at 510), so the more
    // specific structure must win when both are present.
    if sniff_prefix_at(header, 512, b"EFI PART").is_some() {
        return Some(Format::Gpt);
    }

    if let Some(format) = sniff_fixed_prefixes(header) {
        return Some(format);
    }

    if let Some(format) = sniff_zip_container(header) {
        return Some(format);
    }

    if is_mach_o_cafebabe(header) {
        return Some(mach_o_cafebabe_variant(header));
    }

    if let Some(format) = sniff_thin_mach_o(header) {
        return Some(format);
    }

    if let Some(format) = sniff_disc_image(header) {
        return Some(format);
    }

    if is_mpeg_ts(header) {
        return Some(Format::MpegTs);
    }

    if is_vdi(header) {
        return Some(Format::Vdi);
    }

    // MBR last among the boot-sector-signature checks: plenty of non-MBR media (a FAT/NTFS
    // volume, a plain floppy image) carries the same trailing 0x55AA, so this is a catch-all
    // "boot sector present" reading only once nothing more specific has already matched.
    if header.len() >= 512 && header[510] == 0x55 && header[511] == 0xAA {
        return Some(Format::Mbr);
    }

    if let Some(tail) = tail {
        if tail.len() >= 4 && &tail[0..4] == b"koly" {
            return Some(Format::Dmg);
        }
        if tail.len() >= 8 && &tail[0..8] == b"conectix" {
            return Some(Format::Vhd);
        }
    }

    None
}

fn sniff_prefix_at(data: &[u8], offset: usize, needle: &[u8]) -> Option<()> {
    let end = offset.checked_add(needle.len())?;
    if data.len() >= end && &data[offset..end] == needle {
        Some(())
    } else {
        None
    }
}

/// Formats identified entirely by a fixed byte sequence at a fixed offset (almost always 0).
fn sniff_fixed_prefixes(header: &[u8]) -> Option<Format> {
    const PREFIXES: &[(&[u8], Format)] = &[
        (b"7z\xBC\xAF\x27\x1C", Format::SevenZip),
        (b"Rar!\x1A\x07\x00", Format::Rar4),
        (b"Rar!\x1A\x07\x01\x00", Format::Rar5),
        (b"\x1F\x8B", Format::Gzip),
        (b"BZh", Format::Bzip2),
        (b"\xFD7zXZ\x00", Format::Xz),
        (b"\x28\xB5\x2F\xFD", Format::Zstd),
        (b"\x04\x22\x4D\x18", Format::Lz4),
        (b"%PDF-", Format::Pdf),
        (b"\x7FELF", Format::Elf),
        (b"MZ", Format::Pe),
        (b"SQLite format 3\x00", Format::Sqlite),
        (b"\x89PNG\r\n\x1A\n", Format::Png),
        (b"\xFF\xD8\xFF", Format::Jpeg),
        (b"GIF87a", Format::Gif),
        (b"GIF89a", Format::Gif),
        (b"BM", Format::Bmp),
        (b"II*\x00", Format::Tiff),
        (b"MM\x00*", Format::Tiff),
        (b"vhdxfile", Format::Vhdx),
        (b"conectix", Format::Vhd),
        (b"QFI\xFB", Format::Qcow),
        (b"KDMV", Format::Vmdk),
        (b"MSWIM\x00\x00", Format::Wim),
    ];
    // Longest prefix first: RAR5's 8-byte magic extends RAR4's 7-byte one, and a naive
    // shortest-first scan would misclassify every RAR5 file as RAR4.
    let mut candidates: Vec<&(&[u8], Format)> = PREFIXES.iter().collect();
    candidates.sort_by_key(|(needle, _)| std::cmp::Reverse(needle.len()));
    for (needle, format) in candidates {
        if header.len() >= needle.len() && &header[..needle.len()] == *needle {
            return Some(*format);
        }
    }
    if header.len() >= 12 && &header[0..4] == b"RIFF" && &header[8..12] == b"WEBP" {
        return Some(Format::WebP);
    }
    None
}

/// `0xCAFEBABE` is ambiguous: it opens both a Mach-O universal ("fat") binary and a Java
/// `.class` file. The next four big-endian bytes disambiguate -- a fat binary's `nfat_arch`
/// count is always small (real toolchains emit a handful of architecture slices at most), while
/// a class file's minor+major version there starts at major version 45 (Java 1.0) and only
/// grows, the same heuristic `file(1)` itself uses.
fn is_mach_o_cafebabe(header: &[u8]) -> bool {
    header.len() >= 4 && header[0..4] == [0xCA, 0xFE, 0xBA, 0xBE]
}

fn mach_o_cafebabe_variant(header: &[u8]) -> Format {
    let major_version_looking = header
        .get(6..8)
        .map(|b| u16::from_be_bytes([b[0], b[1]]))
        .unwrap_or(0);
    if (45..=1000).contains(&major_version_looking) {
        Format::JavaClass
    } else {
        Format::MachOFat
    }
}

/// Sniffs the thin single-architecture Mach-O magics (`0xFEEDFACE`/`CE`/`CF`/`FE`), separately
/// from the `0xCAFEBABE` fat/class-file case above.
fn sniff_thin_mach_o(header: &[u8]) -> Option<Format> {
    const MAGICS: [[u8; 4]; 4] = [
        [0xFE, 0xED, 0xFA, 0xCE],
        [0xFE, 0xED, 0xFA, 0xCF],
        [0xCE, 0xFA, 0xED, 0xFE],
        [0xCF, 0xFA, 0xED, 0xFE],
    ];
    if header.len() >= 4 && MAGICS.iter().any(|magic| header[0..4] == *magic) {
        Some(Format::MachO)
    } else {
        None
    }
}

/// `MPEG-TS`'s only signature is a `0x47` sync byte every 188 bytes; checking three in a row
/// (rather than one) is the difference between actually recognising the format and matching any
/// file that happens to start with the letter `G`.
fn is_mpeg_ts(header: &[u8]) -> bool {
    const PACKET_LEN: usize = 188;
    header.len() > 2 * PACKET_LEN
        && header[0] == 0x47
        && header[PACKET_LEN] == 0x47
        && header[2 * PACKET_LEN] == 0x47
}

/// A VirtualBox VDI's only fixed-position magic is a little-endian `0xBEDA107F` signature at
/// byte offset 64 (the pre-header ASCII comment occupies the 64 bytes before it); this is more
/// reliable than searching for that comment's text, which a tool that re-saved the image could
/// have altered or truncated.
fn is_vdi(header: &[u8]) -> bool {
    header.len() >= 68 && header[64..68] == [0x7F, 0x10, 0xDA, 0xBE]
}

/// ISO9660 and UDF volume descriptors live in the Volume Recognition Sequence, sectors 16
/// through 20 (2048 bytes each), each starting with a 1-byte structure type followed by a
/// 5-byte standard identifier at `sector_start + 1`. UDF discs carry both a `BEA01`/`NSR0x`
/// bridge sequence and, per the bridge format all real UDF media uses for backward
/// compatibility, an ISO9660 `CD001` descriptor too -- so UDF is checked first, or every UDF
/// disc would be misreported as plain ISO9660.
fn sniff_disc_image(header: &[u8]) -> Option<Format> {
    let mut saw_cd001 = false;
    let mut saw_udf_tag = false;
    for sector in 16..=20usize {
        let id_start = match sector
            .checked_mul(SECTOR_LEN)
            .and_then(|s| s.checked_add(1))
        {
            Some(offset) => offset,
            None => break,
        };
        let id_end = id_start + 5;
        if header.len() < id_end {
            break;
        }
        let id = &header[id_start..id_end];
        match id {
            b"CD001" => saw_cd001 = true,
            b"BEA01" | b"NSR02" | b"NSR03" | b"TEA01" => saw_udf_tag = true,
            _ => {}
        }
    }
    if saw_udf_tag {
        Some(Format::Udf)
    } else if saw_cd001 {
        Some(Format::Iso9660)
    } else {
        None
    }
}

/// The ZIP local-file-header layout (ECMA-376/PKWARE APPNOTE section 4.3.7), read just far
/// enough to name the first entry -- never a full central-directory parse, which the last bytes
/// of a large archive this only sniffs the header of would not even have.
struct ZipLocalEntry<'a> {
    compression_method: u16,
    uncompressed_size: u32,
    name: &'a [u8],
    data: &'a [u8],
}

fn parse_first_zip_entry(header: &[u8]) -> Option<ZipLocalEntry<'_>> {
    if header.len() < 30 || &header[0..4] != b"PK\x03\x04" {
        return None;
    }
    let compression_method = u16::from_le_bytes([header[8], header[9]]);
    let uncompressed_size = u32::from_le_bytes([header[22], header[23], header[24], header[25]]);
    let name_len = u16::from_le_bytes([header[26], header[27]]) as usize;
    let extra_len = u16::from_le_bytes([header[28], header[29]]) as usize;
    let name_start: usize = 30;
    let name_end = name_start.checked_add(name_len)?;
    let data_start = name_end.checked_add(extra_len)?;
    if header.len() < name_end {
        return None;
    }
    let name = &header[name_start..name_end];
    let data = header.get(data_start..).unwrap_or(&[]);
    Some(ZipLocalEntry {
        compression_method,
        uncompressed_size,
        name,
        data,
    })
}

/// Identifies the ZIP-based container formats by their first entry, per the master plan's own
/// "OOXML/ODF (ZIP plus a mimetype entry)": APK by `AndroidManifest.xml` being first (real
/// `aapt`/`aapt2` output always packs it before anything else), a JAR by its manifest, and
/// EPUB/ODF by the OCF/ODF convention of an uncompressed `mimetype` entry declaring which. A
/// plain ZIP, and an OOXML document (identified only by `[Content_Types].xml`, since telling a
/// `.docx` apart from an `.xlsx` needs more than the first entry's name), fall out of the same
/// check. Not reached for an empty (`PK\x05\x06`-only) or spanned (`PK\x07\x08`-first) archive --
/// those are real ZIP files with no first local entry to name, so [`sniff_fixed_prefixes`]'s
/// generic case would need to cover them if this crate grows that far; today they simply fall
/// through to `None` rather than a wrong guess, which is a known small gap, not silently wrong.
fn sniff_zip_container(header: &[u8]) -> Option<Format> {
    let entry = parse_first_zip_entry(header)?;
    match entry.name {
        b"AndroidManifest.xml" => Some(Format::Apk),
        b"META-INF/MANIFEST.MF" => Some(Format::Jar),
        b"[Content_Types].xml" => Some(Format::Ooxml),
        b"mimetype" if entry.compression_method == 0 => {
            let len = (entry.uncompressed_size as usize)
                .min(entry.data.len())
                .min(128);
            let content = &entry.data[..len];
            if content == b"application/epub+zip" {
                Some(Format::Epub)
            } else if content.starts_with(b"application/vnd.oasis.opendocument") {
                Some(Format::Odf)
            } else {
                Some(Format::Zip)
            }
        }
        _ => Some(Format::Zip),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_and_garbage_input_never_panics_and_matches_nothing() {
        assert_eq!(sniff(&[]), None);
        assert_eq!(sniff(b"not a known format, just some plain text"), None);
    }

    #[test]
    fn fixed_prefix_formats_all_match() {
        let cases: &[(&[u8], Format)] = &[
            (b"7z\xBC\xAF\x27\x1C\x00\x04", Format::SevenZip),
            (b"Rar!\x1A\x07\x00extra", Format::Rar4),
            (b"Rar!\x1A\x07\x01\x00extra", Format::Rar5),
            (b"\x1F\x8B\x08\x00", Format::Gzip),
            (b"BZh91AY", Format::Bzip2),
            (b"\xFD7zXZ\x00\x00", Format::Xz),
            (b"\x28\xB5\x2F\xFD\x00", Format::Zstd),
            (b"\x04\x22\x4D\x18\x00", Format::Lz4),
            (b"%PDF-1.7\n", Format::Pdf),
            (b"\x7FELF\x02\x01", Format::Elf),
            (b"MZ\x90\x00", Format::Pe),
            (b"SQLite format 3\x00extra", Format::Sqlite),
            (b"\x89PNG\r\n\x1A\n\x00\x00", Format::Png),
            (b"\xFF\xD8\xFF\xE0", Format::Jpeg),
            (b"GIF87a", Format::Gif),
            (b"GIF89a", Format::Gif),
            (b"BM\x00\x00\x00\x00", Format::Bmp),
            (b"II*\x00\x08\x00", Format::Tiff),
            (b"MM\x00*\x00\x08", Format::Tiff),
            (b"vhdxfile\x00\x00", Format::Vhdx),
            (b"conectix\x00\x00", Format::Vhd),
            (b"QFI\xFB\x00\x00", Format::Qcow),
            (b"KDMV\x01\x00", Format::Vmdk),
            (b"MSWIM\x00\x00\x00", Format::Wim),
        ];
        for (bytes, expected) in cases {
            assert_eq!(sniff(bytes), Some(*expected), "input {:?}", bytes);
        }
    }

    #[test]
    fn rar5_is_not_misdetected_as_rar4() {
        // RAR5's magic extends RAR4's by one byte -- a naive shortest-match-first scan would
        // report every RAR5 file as RAR4 instead.
        assert_eq!(sniff(b"Rar!\x1A\x07\x01\x00trailing"), Some(Format::Rar5));
    }

    #[test]
    fn webp_requires_both_riff_and_the_webp_fourcc() {
        assert_eq!(sniff(b"RIFF\x24\x00\x00\x00WEBPVP8 "), Some(Format::WebP));
        // Plain RIFF (e.g. a WAV file) without the WEBP fourcc must not match.
        assert_eq!(sniff(b"RIFF\x24\x00\x00\x00WAVEfmt "), None);
    }

    #[test]
    fn thin_mach_o_magics_all_match() {
        for magic in [
            [0xFEu8, 0xED, 0xFA, 0xCE],
            [0xFE, 0xED, 0xFA, 0xCF],
            [0xCE, 0xFA, 0xED, 0xFE],
            [0xCF, 0xFA, 0xED, 0xFE],
        ] {
            let mut header = magic.to_vec();
            header.extend_from_slice(&[0u8; 16]);
            assert_eq!(sniff_thin_mach_o(&header), Some(Format::MachO));
        }
    }

    #[test]
    fn cafebabe_disambiguates_fat_mach_o_from_a_java_class_file() {
        // Mach-O fat header: magic, then a small big-endian nfat_arch (2 architecture slices).
        let mut fat = vec![0xCA, 0xFE, 0xBA, 0xBE];
        fat.extend_from_slice(&2u32.to_be_bytes());
        assert_eq!(sniff(&fat), Some(Format::MachOFat));

        // Java class file: magic, then minor_version (2 bytes), then major_version (2 bytes) --
        // major 61 is Java 17's class file version.
        let mut class = vec![0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00];
        class.extend_from_slice(&61u16.to_be_bytes());
        assert_eq!(sniff(&class), Some(Format::JavaClass));
    }

    #[test]
    fn mpeg_ts_needs_three_synced_packets_not_one_stray_byte() {
        let mut stream = vec![0u8; 3 * 188 + 4];
        stream[0] = 0x47;
        stream[188] = 0x47;
        stream[2 * 188] = 0x47;
        assert_eq!(sniff(&stream), Some(Format::MpegTs));

        // A single 'G' at the start of an otherwise unrelated file must not match.
        let mut not_ts = vec![0u8; 3 * 188 + 4];
        not_ts[0] = 0x47;
        assert_eq!(sniff(&not_ts), None);
    }

    #[test]
    fn vdi_matches_its_offset_64_signature() {
        let mut header = vec![0u8; 128];
        header[0..21].copy_from_slice(b"Oracle VM VirtualBox ");
        header[64..68].copy_from_slice(&[0x7F, 0x10, 0xDA, 0xBE]);
        assert_eq!(sniff(&header), Some(Format::Vdi));
    }

    #[test]
    fn iso9660_matches_cd001_at_any_of_its_three_documented_sectors() {
        for sector in [16usize, 17, 18] {
            let mut header = vec![0u8; HEADER_LEN];
            header[sector * SECTOR_LEN + 1..sector * SECTOR_LEN + 6].copy_from_slice(b"CD001");
            assert_eq!(sniff(&header), Some(Format::Iso9660), "sector {sector}");
        }
    }

    #[test]
    fn udf_wins_over_the_iso9660_bridge_descriptor_it_always_also_carries() {
        let mut header = vec![0u8; HEADER_LEN];
        // Every real UDF disc is bridged with a CD001 descriptor for backward compatibility --
        // both must be present, and UDF must be the answer, not ISO9660.
        header[16 * SECTOR_LEN + 1..16 * SECTOR_LEN + 6].copy_from_slice(b"CD001");
        header[17 * SECTOR_LEN + 1..17 * SECTOR_LEN + 6].copy_from_slice(b"BEA01");
        header[18 * SECTOR_LEN + 1..18 * SECTOR_LEN + 6].copy_from_slice(b"NSR02");
        assert_eq!(sniff(&header), Some(Format::Udf));
    }

    #[test]
    fn gpt_wins_over_the_protective_mbr_signature_it_always_also_carries() {
        let mut header = vec![0u8; 520];
        header[510] = 0x55;
        header[511] = 0xAA;
        header[512..520].copy_from_slice(b"EFI PART");
        assert_eq!(sniff(&header), Some(Format::Gpt));
    }

    #[test]
    fn mbr_matches_when_no_more_specific_structure_is_present() {
        let mut header = vec![0u8; 512];
        header[510] = 0x55;
        header[511] = 0xAA;
        assert_eq!(sniff(&header), Some(Format::Mbr));
    }

    #[test]
    fn dmg_and_vhd_are_only_recognised_from_the_tail() {
        let header = vec![0u8; 512];
        assert_eq!(sniff_with_tail(&header, None), None);

        let mut dmg_tail = vec![0u8; TAIL_LEN];
        dmg_tail[0..4].copy_from_slice(b"koly");
        assert_eq!(sniff_with_tail(&header, Some(&dmg_tail)), Some(Format::Dmg));

        let mut vhd_tail = vec![0u8; TAIL_LEN];
        vhd_tail[0..8].copy_from_slice(b"conectix");
        assert_eq!(sniff_with_tail(&header, Some(&vhd_tail)), Some(Format::Vhd));
    }

    #[test]
    fn a_dynamic_vhd_is_recognised_from_its_header_copy_without_needing_the_tail() {
        assert_eq!(sniff(b"conectix\x00\x00"), Some(Format::Vhd));
    }

    fn zip_local_entry(name: &[u8], stored_content: Option<&[u8]>) -> Vec<u8> {
        let (compression_method, data): (u16, &[u8]) = match stored_content {
            Some(content) => (0, content),
            None => (8, b"\x00\x00\x00\x00"),
        };
        let mut entry = Vec::new();
        entry.extend_from_slice(b"PK\x03\x04");
        entry.extend_from_slice(&20u16.to_le_bytes()); // version needed
        entry.extend_from_slice(&0u16.to_le_bytes()); // flags
        entry.extend_from_slice(&compression_method.to_le_bytes());
        entry.extend_from_slice(&0u16.to_le_bytes()); // mod time
        entry.extend_from_slice(&0u16.to_le_bytes()); // mod date
        entry.extend_from_slice(&0u32.to_le_bytes()); // crc32
        entry.extend_from_slice(&(data.len() as u32).to_le_bytes()); // compressed size
        entry.extend_from_slice(&(data.len() as u32).to_le_bytes()); // uncompressed size
        entry.extend_from_slice(&(name.len() as u16).to_le_bytes());
        entry.extend_from_slice(&0u16.to_le_bytes()); // extra field length
        entry.extend_from_slice(name);
        entry.extend_from_slice(data);
        entry
    }

    #[test]
    fn zip_container_formats_identified_by_first_entry() {
        assert_eq!(
            sniff(&zip_local_entry(b"AndroidManifest.xml", None)),
            Some(Format::Apk)
        );
        assert_eq!(
            sniff(&zip_local_entry(b"META-INF/MANIFEST.MF", None)),
            Some(Format::Jar)
        );
        assert_eq!(
            sniff(&zip_local_entry(b"[Content_Types].xml", None)),
            Some(Format::Ooxml)
        );
        assert_eq!(
            sniff(&zip_local_entry(b"mimetype", Some(b"application/epub+zip"))),
            Some(Format::Epub)
        );
        assert_eq!(
            sniff(&zip_local_entry(
                b"mimetype",
                Some(b"application/vnd.oasis.opendocument.text")
            )),
            Some(Format::Odf)
        );
        assert_eq!(
            sniff(&zip_local_entry(b"readme.txt", None)),
            Some(Format::Zip)
        );
    }

    #[test]
    fn a_compressed_mimetype_entry_is_not_misread_as_an_ocf_declaration() {
        // The OCF convention requires the mimetype entry to be STORED, not compressed; a
        // compressed entry that happens to be named "mimetype" is just an ordinary ZIP member.
        let entry = zip_local_entry(b"mimetype", None);
        assert_eq!(sniff(&entry), Some(Format::Zip));
    }

    #[test]
    fn every_format_has_a_label_and_the_documented_mime_types_are_present() {
        // Exercised for its own sake: a Format that panics naming itself would be a real bug.
        let all = [
            Format::Zip,
            Format::SevenZip,
            Format::Rar4,
            Format::Rar5,
            Format::Gzip,
            Format::Bzip2,
            Format::Xz,
            Format::Zstd,
            Format::Lz4,
            Format::Pdf,
            Format::Elf,
            Format::Pe,
            Format::MachO,
            Format::MachOFat,
            Format::JavaClass,
            Format::Sqlite,
            Format::Png,
            Format::Jpeg,
            Format::Gif,
            Format::Bmp,
            Format::WebP,
            Format::Tiff,
            Format::Iso9660,
            Format::Udf,
            Format::Mbr,
            Format::Gpt,
            Format::Dmg,
            Format::Vhd,
            Format::Vhdx,
            Format::Qcow,
            Format::Vmdk,
            Format::Vdi,
            Format::Wim,
            Format::MpegTs,
            Format::Apk,
            Format::Jar,
            Format::Epub,
            Format::Ooxml,
            Format::Odf,
        ];
        for format in all {
            assert!(!format.label().is_empty());
        }
        assert_eq!(Format::Png.mime(), Some("image/png"));
        assert_eq!(Format::Mbr.mime(), None);
        assert_eq!(Format::Ooxml.mime(), None);
    }
}
