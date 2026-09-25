//! The archive listing codec, writer half (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section
//! 2.2). A full listing leaves the decoder process through a pipe the UI process owns, one record
//! per header **as the header is read**, so a 200,000-entry tarball never exists as a Kotlin
//! object graph in `:decoders` and bytes flow during the pass (the liveness signal the client
//! watches). The reader half is Kotlin (`archive/ArchiveListingCodec.kt`); the two are held
//! together by a committed golden file (`app/src/test/resources/fixtures/archives/*.fzl`) that a
//! Rust test asserts this writer reproduces byte for byte and a Kotlin test decodes.
//!
//! Format, all integers little-endian:
//!
//! ```text
//! magic     "FZL1"
//! record    tag 0x01 | ordinal u32 | path u32 len + bytes | kind u8 | flags u8 |
//!           uncompressed u64 | mtime i64 | mode u32 | [link target u32 len + bytes]
//! trailer   tag 0xFF | count u32 | partial u8
//! ```
//!
//! `ordinal` is [EntryMetadata::ordinal]: the entry's position among the archive's headers (the
//! index of the `archive_read_next_header` call that produced it, counting from 0 and including a
//! format's own root directory header when it lists one, which is itself never a record); it is
//! what `extract_entry_at` walks to, so two members with one path stay two documents. `path` is the
//! entry's pathname **as the engine reports it**: the raw bytes when they are UTF-8, and
//! `String::from_utf8_lossy` of them with `FLAG_NAME_LOSSY` set when they are not -- the same
//! string `EntryMetadata::path` carries and `extract_entry_at` compares against, so the id built
//! from a listing always names the header it came from. (The design says "as libarchive gave it";
//! writing the undecoded bytes would leave the Kotlin reader to lossy-decode them itself, and
//! Java's replacement rules differ from Rust's for some malformed sequences, which would break the
//! byte-exact check -- recorded as a deviation.) The reader normalises paths for the tree; this
//! writer never does. `uncompressed` is 0 and `mtime` is 0 when the matching `*_UNKNOWN` flag is
//! set. `partial` is 1 when the header pass stopped on damage after at least one record.

use crate::EntryKind;
use crate::EntryMetadata;
use std::io;
use std::io::BufWriter;
use std::io::Write;

pub const MAGIC: &[u8; 4] = b"FZL1";
pub const TAG_RECORD: u8 = 0x01;
pub const TAG_TRAILER: u8 = 0xFF;

pub const FLAG_ENCRYPTED_DATA: u8 = 0x01;
pub const FLAG_ENCRYPTED_METADATA: u8 = 0x02;
pub const FLAG_SIZE_UNKNOWN: u8 = 0x04;
pub const FLAG_MTIME_UNKNOWN: u8 = 0x08;
pub const FLAG_NAME_LOSSY: u8 = 0x10;
pub const FLAG_HAS_LINK_TARGET: u8 = 0x20;

pub const KIND_FILE: u8 = 0;
pub const KIND_DIRECTORY: u8 = 1;
pub const KIND_SYMLINK: u8 = 2;
pub const KIND_HARDLINK: u8 = 3;
pub const KIND_OTHER: u8 = 4;

/// The codec's kind byte for an [EntryKind]; the Kotlin reader's `KIND_*` constants match.
pub fn kind_code(kind: EntryKind) -> u8 {
    match kind {
        EntryKind::File => KIND_FILE,
        EntryKind::Directory => KIND_DIRECTORY,
        EntryKind::Symlink => KIND_SYMLINK,
        EntryKind::Hardlink => KIND_HARDLINK,
        EntryKind::Other => KIND_OTHER,
    }
}

/// Writes one listing: [ListingWriter::new] emits the magic, [ListingWriter::record] one record per
/// entry, [ListingWriter::finish] the trailer. Buffered, so a pipe sees a few large writes rather
/// than a dozen tiny ones per entry; [ListingWriter::finish] flushes.
pub struct ListingWriter<W: Write> {
    out: BufWriter<W>,
    count: u32,
}

impl<W: Write> ListingWriter<W> {
    pub fn new(sink: W) -> io::Result<Self> {
        let mut out = BufWriter::with_capacity(64 * 1024, sink);
        out.write_all(MAGIC)?;
        Ok(ListingWriter { out, count: 0 })
    }

    /// How many records have been written so far.
    pub fn count(&self) -> u32 {
        self.count
    }

    /// Writes one record; the ordinal written is [EntryMetadata::ordinal].
    pub fn record(&mut self, entry: &EntryMetadata) -> io::Result<()> {
        let mut flags = 0u8;
        if entry.encrypted_data {
            flags |= FLAG_ENCRYPTED_DATA;
        }
        if entry.encrypted_metadata {
            flags |= FLAG_ENCRYPTED_METADATA;
        }
        if entry.uncompressed.is_none() {
            flags |= FLAG_SIZE_UNKNOWN;
        }
        if entry.mtime.is_none() {
            flags |= FLAG_MTIME_UNKNOWN;
        }
        if entry.name_lossy {
            flags |= FLAG_NAME_LOSSY;
        }
        if entry.link_target.is_some() {
            flags |= FLAG_HAS_LINK_TARGET;
        }
        self.out.write_all(&[TAG_RECORD])?;
        self.out.write_all(&entry.ordinal.to_le_bytes())?;
        write_bytes(&mut self.out, entry.path.as_bytes())?;
        self.out.write_all(&[kind_code(entry.kind), flags])?;
        self.out
            .write_all(&entry.uncompressed.unwrap_or(0).to_le_bytes())?;
        self.out
            .write_all(&entry.mtime.unwrap_or(0).to_le_bytes())?;
        self.out.write_all(&entry.mode.to_le_bytes())?;
        if let Some(target) = &entry.link_target {
            write_bytes(&mut self.out, target.as_bytes())?;
        }
        self.count = self.count.saturating_add(1);
        Ok(())
    }

    /// Writes the trailer and flushes, handing the sink back.
    pub fn finish(mut self, partial: bool) -> io::Result<W> {
        self.out.write_all(&[TAG_TRAILER])?;
        self.out.write_all(&self.count.to_le_bytes())?;
        self.out.write_all(&[u8::from(partial)])?;
        self.out.flush()?;
        self.out
            .into_inner()
            .map_err(|e| io::Error::new(e.error().kind(), e.error().to_string()))
    }
}

fn write_bytes<W: Write>(out: &mut W, bytes: &[u8]) -> io::Result<()> {
    let len = u32::try_from(bytes.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "listing string over 4 GiB"))?;
    out.write_all(&len.to_le_bytes())?;
    out.write_all(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(ordinal: u32, path: &str) -> EntryMetadata {
        EntryMetadata {
            ordinal,
            path: path.to_string(),
            name_lossy: false,
            kind: EntryKind::File,
            link_target: None,
            uncompressed: Some(11),
            compressed: None,
            mtime: Some(1_577_836_800),
            mode: 0o644,
            encrypted_data: false,
            encrypted_metadata: false,
        }
    }

    #[test]
    fn a_file_record_is_laid_out_field_by_field() {
        let mut writer = ListingWriter::new(Vec::new()).unwrap();
        writer.record(&entry(3, "a")).unwrap();
        let bytes = writer.finish(false).unwrap();
        let mut expected = Vec::new();
        expected.extend_from_slice(b"FZL1");
        expected.push(0x01);
        expected.extend_from_slice(&3u32.to_le_bytes());
        expected.extend_from_slice(&1u32.to_le_bytes());
        expected.push(b'a');
        expected.push(KIND_FILE);
        expected.push(0);
        expected.extend_from_slice(&11u64.to_le_bytes());
        expected.extend_from_slice(&1_577_836_800i64.to_le_bytes());
        expected.extend_from_slice(&0o644u32.to_le_bytes());
        expected.push(0xFF);
        expected.extend_from_slice(&1u32.to_le_bytes());
        expected.push(0);
        assert_eq!(bytes, expected);
    }

    #[test]
    fn every_flag_and_the_link_target_are_written() {
        let mut writer = ListingWriter::new(Vec::new()).unwrap();
        let link = EntryMetadata {
            ordinal: 0,
            path: "l".to_string(),
            name_lossy: true,
            kind: EntryKind::Symlink,
            link_target: Some("t".to_string()),
            uncompressed: None,
            compressed: None,
            mtime: None,
            mode: 0o777,
            encrypted_data: true,
            encrypted_metadata: true,
        };
        writer.record(&link).unwrap();
        let bytes = writer.finish(true).unwrap();
        // magic(4) tag(1) ordinal(4) len(4) path(1) kind(1) flags(1) ...
        assert_eq!(bytes[14], KIND_SYMLINK);
        assert_eq!(
            bytes[15],
            FLAG_ENCRYPTED_DATA
                | FLAG_ENCRYPTED_METADATA
                | FLAG_SIZE_UNKNOWN
                | FLAG_MTIME_UNKNOWN
                | FLAG_NAME_LOSSY
                | FLAG_HAS_LINK_TARGET
        );
        // uncompressed(8) mtime(8) mode(4) then the link target.
        assert_eq!(&bytes[36..40], &1u32.to_le_bytes());
        assert_eq!(bytes[40], b't');
        // The trailer says partial.
        assert_eq!(bytes[bytes.len() - 1], 1);
        assert_eq!(bytes[bytes.len() - 6], TAG_TRAILER);
    }

    #[test]
    fn kind_codes_are_total_and_distinct() {
        let codes: Vec<u8> = [
            EntryKind::File,
            EntryKind::Directory,
            EntryKind::Symlink,
            EntryKind::Hardlink,
            EntryKind::Other,
        ]
        .into_iter()
        .map(kind_code)
        .collect();
        assert_eq!(codes, vec![0, 1, 2, 3, 4]);
    }
}
