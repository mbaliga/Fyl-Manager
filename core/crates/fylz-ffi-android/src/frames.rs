//! The extraction frame codec, writer half (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section
//! 2.3 step 4). One `archive_extract_ranges` call streams every selected entry of an archive
//! through a **single pipe** the UI process owns; this writer turns `fylz_archive`'s [BlockSink]
//! callbacks into a framed byte stream the Kotlin `ExtractFrameReader` demultiplexes into staged
//! destinations. The two halves are held together by a committed golden file
//! (`app/src/test/resources/fixtures/archives/tree.fzx`, written from `tree.tar.zst`) that a Rust
//! test asserts this writer reproduces byte for byte and a Kotlin test decodes.
//!
//! Format, all integers little-endian:
//!
//! ```text
//! MAGIC  "FZX1"
//! BEGIN  0x01 ordinal:u32 declared:i64(-1 unknown) kind:u8(1 file,2 dir,3 symlink,4 hardlink,5 other) path_len:u32(<=65536) raw_path
//! DATA   0x02 ordinal:u32 len:u32(1..=1 MiB; larger libarchive blocks are split) bytes
//! END    0x03 ordinal:u32 bytes:u64 warn:u8(0 none,1 other) msg_len:u16 msg      (msg_len always present)
//! FAIL   0x04 ordinal:u32 kind:u8(1 crc,2 size,3 decode,0 other) msg_len:u16 msg (after BEGIN+DATA, or alone for a header-level failure)
//! DONE   0x05 entries:u32 bytes:u64 failed:u32
//! ABORT  0x06 msg_len:u16 msg
//! ```
//!
//! The magic is written with the **first frame**, not at construction: a call the policy refuses
//! writes nothing at all (the reader accepts an empty stream with a non-OK result), while a pass
//! that dies before its first entry still gets `MAGIC ABORT` (the reader requires the magic with an
//! OK or CORRUPT result). The `ArchiveExtractResult` the call returns is the **authority** for the
//! outcome; frames are data. `raw_path` is the engine's (lossily decoded) path string, the same
//! string the listing carried for the ordinal, so the reader's byte-exact comparison with the plan
//! sees what the plan was built from (M3.3's recorded deviation, REVIEW_QUEUE M3.3 item 27).
//!
//! A write that finds the pipe's reader gone (`EPIPE`) is [ArchiveError::Cancelled]: the UI process
//! closed the read end to cancel, and the engine's pass ends there (SIGPIPE is ignored by this
//! crate, see the crate doc).

use fylz_archive::ArchiveError;
use fylz_archive::BlockSink;
use fylz_archive::EntryKind;
use fylz_archive::EntryMetadata;
use fylz_archive::FailKind;
use fylz_archive::Warning;
use std::io;
use std::io::BufWriter;
use std::io::Write;

pub const MAGIC: &[u8; 4] = b"FZX1";

pub const TAG_BEGIN: u8 = 0x01;
pub const TAG_DATA: u8 = 0x02;
pub const TAG_END: u8 = 0x03;
pub const TAG_FAIL: u8 = 0x04;
pub const TAG_DONE: u8 = 0x05;
pub const TAG_ABORT: u8 = 0x06;

pub const KIND_FILE: u8 = 1;
pub const KIND_DIRECTORY: u8 = 2;
pub const KIND_SYMLINK: u8 = 3;
pub const KIND_HARDLINK: u8 = 4;
pub const KIND_OTHER: u8 = 5;

pub const WARN_NONE: u8 = 0;
pub const WARN_OTHER: u8 = 1;

pub const FAIL_OTHER: u8 = 0;
pub const FAIL_CRC: u8 = 1;
pub const FAIL_SIZE: u8 = 2;
pub const FAIL_DECODE: u8 = 3;

/// The most bytes one `DATA` frame carries; a larger libarchive block is split.
pub const MAX_DATA_FRAME_BYTES: usize = 1024 * 1024;

/// The longest path a `BEGIN` frame may carry (the listing codec's own string bound); a longer
/// one is a stand-alone `FAIL` for that ordinal instead.
pub const MAX_PATH_BYTES: usize = 64 * 1024;

/// The reader's declared-size sentinel for an entry whose header does not say.
pub const DECLARED_UNKNOWN: i64 = -1;

/// The codec's kind byte for an [EntryKind].
pub fn kind_code(kind: EntryKind) -> u8 {
    match kind {
        EntryKind::File => KIND_FILE,
        EntryKind::Directory => KIND_DIRECTORY,
        EntryKind::Symlink => KIND_SYMLINK,
        EntryKind::Hardlink => KIND_HARDLINK,
        EntryKind::Other => KIND_OTHER,
    }
}

/// The codec's kind byte for a [FailKind].
pub fn fail_code(kind: FailKind) -> u8 {
    match kind {
        FailKind::Crc => FAIL_CRC,
        FailKind::Size => FAIL_SIZE,
        FailKind::Decode => FAIL_DECODE,
        FailKind::Other => FAIL_OTHER,
    }
}

/// Writes one extraction stream: the [BlockSink] callbacks become frames, [FrameWriter::finish]
/// the `DONE` trailer, [FrameWriter::abort] the `ABORT` one. Buffered (64 KiB), so a pipe sees a
/// few large writes per entry rather than a header, a body and a trailer each; both terminals
/// flush.
pub struct FrameWriter<W: Write> {
    out: BufWriter<W>,
    started: bool,
    entries: u32,
    bytes: u64,
    failed: u32,
}

impl<W: Write> FrameWriter<W> {
    pub fn new(sink: W) -> Self {
        FrameWriter {
            out: BufWriter::with_capacity(64 * 1024, sink),
            started: false,
            entries: 0,
            bytes: 0,
            failed: 0,
        }
    }

    /// Entries ended, bytes ended, entries failed -- what `DONE` carries.
    pub fn counts(&self) -> (u32, u64, u32) {
        (self.entries, self.bytes, self.failed)
    }

    /// Whether any frame (hence the magic) has been written.
    pub fn started(&self) -> bool {
        self.started
    }

    fn start(&mut self) -> Result<(), ArchiveError> {
        if !self.started {
            self.started = true;
            self.out.write_all(MAGIC).map_err(map_io)?;
        }
        Ok(())
    }

    fn put(&mut self, bytes: &[u8]) -> Result<(), ArchiveError> {
        self.out.write_all(bytes).map_err(map_io)
    }

    fn put_message(&mut self, message: &str) -> Result<(), ArchiveError> {
        let bytes = message.as_bytes();
        let cut = truncate_utf8(bytes, u16::MAX as usize);
        self.put(&(cut.len() as u16).to_le_bytes())?;
        self.put(cut)
    }

    /// `DONE`, flushed; hands the sink back.
    pub fn finish(mut self) -> Result<W, ArchiveError> {
        self.start()?;
        self.put(&[TAG_DONE])?;
        self.put(&self.entries.to_le_bytes())?;
        self.put(&self.bytes.to_le_bytes())?;
        self.put(&self.failed.to_le_bytes())?;
        self.into_sink()
    }

    /// `ABORT` with `message`, flushed (the magic first when nothing was written yet); hands the
    /// sink back.
    pub fn abort(mut self, message: &str) -> Result<W, ArchiveError> {
        self.start()?;
        self.put(&[TAG_ABORT])?;
        self.put_message(message)?;
        self.into_sink()
    }

    fn into_sink(mut self) -> Result<W, ArchiveError> {
        self.out.flush().map_err(map_io)?;
        self.out
            .into_inner()
            .map_err(|e| map_io(io::Error::new(e.error().kind(), e.error().to_string())))
    }
}

impl<W: Write> BlockSink for FrameWriter<W> {
    fn begin(&mut self, entry: &EntryMetadata) -> Result<bool, ArchiveError> {
        self.start()?;
        let path = entry.path.as_bytes();
        if path.len() > MAX_PATH_BYTES {
            self.failed(
                entry.ordinal,
                FailKind::Other,
                &format!("entry path is longer than {MAX_PATH_BYTES} bytes"),
            )?;
            return Ok(false);
        }
        self.put(&[TAG_BEGIN])?;
        self.put(&entry.ordinal.to_le_bytes())?;
        let declared = entry
            .uncompressed
            .and_then(|size| i64::try_from(size).ok())
            .unwrap_or(DECLARED_UNKNOWN);
        self.put(&declared.to_le_bytes())?;
        self.put(&[kind_code(entry.kind)])?;
        self.put(&(path.len() as u32).to_le_bytes())?;
        self.put(path)?;
        Ok(true)
    }

    fn write(&mut self, ordinal: u32, block: &[u8]) -> Result<(), ArchiveError> {
        for chunk in block.chunks(MAX_DATA_FRAME_BYTES) {
            self.put(&[TAG_DATA])?;
            self.put(&ordinal.to_le_bytes())?;
            self.put(&(chunk.len() as u32).to_le_bytes())?;
            self.put(chunk)?;
        }
        Ok(())
    }

    fn end(
        &mut self,
        ordinal: u32,
        bytes: u64,
        warning: Option<Warning>,
    ) -> Result<(), ArchiveError> {
        self.put(&[TAG_END])?;
        self.put(&ordinal.to_le_bytes())?;
        self.put(&bytes.to_le_bytes())?;
        match warning {
            None => {
                self.put(&[WARN_NONE])?;
                self.put(&0u16.to_le_bytes())?;
            }
            Some(Warning::Other(message)) => {
                self.put(&[WARN_OTHER])?;
                self.put_message(&message)?;
            }
        }
        self.entries = self.entries.saturating_add(1);
        self.bytes = self.bytes.saturating_add(bytes);
        Ok(())
    }

    fn failed(&mut self, ordinal: u32, kind: FailKind, message: &str) -> Result<(), ArchiveError> {
        self.start()?;
        self.put(&[TAG_FAIL])?;
        self.put(&ordinal.to_le_bytes())?;
        self.put(&[fail_code(kind)])?;
        self.put_message(message)?;
        self.failed = self.failed.saturating_add(1);
        Ok(())
    }
}

/// An `EPIPE` on the sink is the UI process cancelling (it closed the read end): the pass ends as
/// [ArchiveError::Cancelled], not as a damaged archive. Anything else is a real write failure.
fn map_io(error: io::Error) -> ArchiveError {
    if error.kind() == io::ErrorKind::BrokenPipe {
        ArchiveError::Cancelled
    } else {
        ArchiveError::Fatal(format!("writing the extraction sink: {error}"))
    }
}

/// The longest prefix of `bytes` within `max` that does not split a UTF-8 sequence.
fn truncate_utf8(bytes: &[u8], max: usize) -> &[u8] {
    if bytes.len() <= max {
        return bytes;
    }
    let mut end = max;
    while end > 0 && (bytes[end] & 0xC0) == 0x80 {
        end -= 1;
    }
    &bytes[..end]
}

#[cfg(test)]
mod tests {
    use super::*;
    use fylz_archive::extract_blocks;
    use fylz_archive::ExtractLimits;
    use fylz_archive::Selection;
    use std::fs;
    use std::fs::File;
    use std::os::fd::AsRawFd;
    use std::path::Path;
    use std::path::PathBuf;

    fn entry(ordinal: u32, path: &str, kind: EntryKind, size: Option<u64>) -> EntryMetadata {
        EntryMetadata {
            ordinal,
            path: path.to_string(),
            name_lossy: false,
            kind,
            link_target: None,
            uncompressed: size,
            compressed: None,
            mtime: None,
            mode: 0o644,
            encrypted_data: false,
            encrypted_metadata: false,
        }
    }

    fn golden_path() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../app/src/test/resources/fixtures/archives/tree.fzx")
    }

    fn fixture(name: &str) -> File {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/archives")
            .join(name);
        File::open(&path).unwrap_or_else(|e| panic!("opening fixture {}: {e}", path.display()))
    }

    #[test]
    fn every_frame_is_laid_out_field_by_field() {
        let mut writer = FrameWriter::new(Vec::new());
        assert!(!writer.started());
        assert!(writer
            .begin(&entry(3, "a", EntryKind::File, Some(5)))
            .unwrap());
        writer.write(3, b"hello").unwrap();
        writer.end(3, 5, None).unwrap();
        assert!(writer
            .begin(&entry(4, "d/", EntryKind::Directory, None))
            .unwrap());
        writer
            .end(4, 0, Some(Warning::Other("odd".into())))
            .unwrap();
        writer.failed(7, FailKind::Crc, "bad").unwrap();
        assert_eq!(writer.counts(), (2, 5, 1));
        let bytes = writer.finish().unwrap();
        let mut expected = Vec::new();
        expected.extend_from_slice(b"FZX1");
        expected.push(TAG_BEGIN);
        expected.extend_from_slice(&3u32.to_le_bytes());
        expected.extend_from_slice(&5i64.to_le_bytes());
        expected.push(KIND_FILE);
        expected.extend_from_slice(&1u32.to_le_bytes());
        expected.push(b'a');
        expected.push(TAG_DATA);
        expected.extend_from_slice(&3u32.to_le_bytes());
        expected.extend_from_slice(&5u32.to_le_bytes());
        expected.extend_from_slice(b"hello");
        expected.push(TAG_END);
        expected.extend_from_slice(&3u32.to_le_bytes());
        expected.extend_from_slice(&5u64.to_le_bytes());
        expected.push(WARN_NONE);
        expected.extend_from_slice(&0u16.to_le_bytes());
        expected.push(TAG_BEGIN);
        expected.extend_from_slice(&4u32.to_le_bytes());
        expected.extend_from_slice(&(-1i64).to_le_bytes());
        expected.push(KIND_DIRECTORY);
        expected.extend_from_slice(&2u32.to_le_bytes());
        expected.extend_from_slice(b"d/");
        expected.push(TAG_END);
        expected.extend_from_slice(&4u32.to_le_bytes());
        expected.extend_from_slice(&0u64.to_le_bytes());
        expected.push(WARN_OTHER);
        expected.extend_from_slice(&3u16.to_le_bytes());
        expected.extend_from_slice(b"odd");
        expected.push(TAG_FAIL);
        expected.extend_from_slice(&7u32.to_le_bytes());
        expected.push(FAIL_CRC);
        expected.extend_from_slice(&3u16.to_le_bytes());
        expected.extend_from_slice(b"bad");
        expected.push(TAG_DONE);
        expected.extend_from_slice(&2u32.to_le_bytes());
        expected.extend_from_slice(&5u64.to_le_bytes());
        expected.extend_from_slice(&1u32.to_le_bytes());
        assert_eq!(bytes, expected);
    }

    #[test]
    fn a_block_over_one_mebibyte_is_split_into_frames_of_at_most_that() {
        let mut writer = FrameWriter::new(Vec::new());
        writer
            .begin(&entry(0, "big", EntryKind::File, None))
            .unwrap();
        let block = vec![7u8; 3 * MAX_DATA_FRAME_BYTES + 5];
        writer.write(0, &block).unwrap();
        writer.end(0, block.len() as u64, None).unwrap();
        let bytes = writer.finish().unwrap();
        // Walk the frames: after the magic and BEGIN, four DATA frames (1 MiB x3 + 5 bytes).
        let mut at = 4 + 1 + 4 + 8 + 1 + 4 + 3;
        let mut lengths = Vec::new();
        while bytes[at] == TAG_DATA {
            let len = u32::from_le_bytes(bytes[at + 5..at + 9].try_into().unwrap()) as usize;
            lengths.push(len);
            at += 9 + len;
        }
        assert_eq!(
            lengths,
            vec![
                MAX_DATA_FRAME_BYTES,
                MAX_DATA_FRAME_BYTES,
                MAX_DATA_FRAME_BYTES,
                5
            ]
        );
        assert_eq!(bytes[at], TAG_END);
    }

    #[test]
    fn abort_writes_the_magic_first_when_nothing_was_written_and_a_refusal_writes_nothing() {
        let bytes = FrameWriter::new(Vec::new()).abort("dead").unwrap();
        let mut expected = b"FZX1".to_vec();
        expected.push(TAG_ABORT);
        expected.extend_from_slice(&4u16.to_le_bytes());
        expected.extend_from_slice(b"dead");
        assert_eq!(bytes, expected);
        // A writer never handed a frame has written nothing: that is the refusal case.
        let untouched = FrameWriter::new(Vec::new());
        assert!(!untouched.started());
    }

    #[test]
    fn an_over_long_path_is_a_stand_alone_fail_not_a_begin() {
        let mut writer = FrameWriter::new(Vec::new());
        let long = "x".repeat(MAX_PATH_BYTES + 1);
        assert!(!writer
            .begin(&entry(9, &long, EntryKind::File, None))
            .unwrap());
        let bytes = writer.finish().unwrap();
        assert_eq!(bytes[4], TAG_FAIL);
        assert_eq!(bytes[9], FAIL_OTHER);
        assert_eq!(writer_counts(&bytes), (0, 0, 1));
    }

    /// The DONE trailer's three counts, from the end of a finished stream.
    fn writer_counts(bytes: &[u8]) -> (u32, u64, u32) {
        let n = bytes.len();
        assert_eq!(bytes[n - 17], TAG_DONE);
        (
            u32::from_le_bytes(bytes[n - 16..n - 12].try_into().unwrap()),
            u64::from_le_bytes(bytes[n - 12..n - 4].try_into().unwrap()),
            u32::from_le_bytes(bytes[n - 4..].try_into().unwrap()),
        )
    }

    #[test]
    fn a_message_is_cut_to_u16_without_splitting_a_character() {
        let long = "é".repeat(40_000); // 80,000 bytes
        let mut writer = FrameWriter::new(Vec::new());
        writer.failed(1, FailKind::Decode, &long).unwrap();
        let bytes = writer.finish().unwrap();
        let len = u16::from_le_bytes(bytes[10..12].try_into().unwrap()) as usize;
        assert_eq!(len, 65_534, "an even cut: every character is two bytes");
        assert!(std::str::from_utf8(&bytes[12..12 + len]).is_ok());
    }

    #[test]
    fn broken_pipe_on_the_sink_is_cancelled_not_corrupt() {
        struct Broken;
        impl Write for Broken {
            fn write(&mut self, _: &[u8]) -> io::Result<usize> {
                Err(io::Error::from(io::ErrorKind::BrokenPipe))
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        let mut writer = FrameWriter::new(Broken);
        writer.begin(&entry(0, "a", EntryKind::File, None)).unwrap();
        let big = vec![0u8; 128 * 1024];
        assert!(matches!(
            writer.write(0, &big),
            Err(ArchiveError::Cancelled)
        ));
    }

    /// The golden stream for the Kotlin reader: `tree.tar.zst` extracted whole through this writer.
    /// `FYLZ_WRITE_GOLDEN=1` (re)writes it; without it the committed bytes must match exactly.
    #[test]
    fn golden_extraction_stream_matches_the_committed_fzx_file() {
        let write = std::env::var_os("FYLZ_WRITE_GOLDEN").is_some_and(|v| v == "1");
        let mut writer = FrameWriter::new(Vec::new());
        let report = extract_blocks(
            fixture("tree.tar.zst").as_raw_fd(),
            &Selection::All,
            &ExtractLimits::default(),
            &mut writer,
        )
        .unwrap();
        // 40 files, 9 directory rows, a symlink and a hardlink all begin and end; nothing fails.
        assert_eq!(report.entries_written, 51);
        assert_eq!(report.entries_failed, 0);
        let bytes = writer.finish().unwrap();
        let path = golden_path();
        if write {
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(&path, &bytes).unwrap();
            eprintln!("wrote {} ({} bytes)", path.display(), bytes.len());
            return;
        }
        let committed = fs::read(&path).unwrap_or_else(|e| {
            panic!(
                "{}: {e} (run `FYLZ_WRITE_GOLDEN=1 cargo test -p fylz-ffi-android golden` to write it)",
                path.display()
            )
        });
        assert!(
            committed == bytes,
            "the writer no longer reproduces the committed {} ({} vs {} bytes); regenerate it with \
             FYLZ_WRITE_GOLDEN=1 and update the Kotlin reader test in lockstep",
            path.display(),
            committed.len(),
            bytes.len()
        );
    }
}
