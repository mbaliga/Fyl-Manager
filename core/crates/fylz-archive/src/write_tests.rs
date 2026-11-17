//! Tests for M3.5a: the `write.rs` engine (`docs/agent/DESIGN-M35-CREATE.md` sections 2.5, 2.8).
//! [FrameBuilder] constructs an `FZW1` byte stream by hand, the write-side mirror of how
//! `fylz-ffi-android`'s own `frames.rs` tests build `FZX1` streams; every test here drives
//! [crate::write::write_frames_io] over an in-memory buffer and then, where a real archive comes
//! out, reads it back with this crate's own [crate::inspect]/[crate::extract] -- the same round
//! trip the design asks for, and the only way to prove the *engine's* writer against the
//! *engine's* own reader without a second archive tool in the loop.

use crate::extract;
use crate::extract_tests::TempProvider;
use crate::inspect;
use crate::policy::Limits;
use crate::tests::tempdir;
use crate::write::write_frames;
use crate::write::write_frames_io;
use crate::write::FormatOptions;
use crate::write::WriteFormat;
use crate::write::KIND_DIRECTORY;
use crate::write::KIND_FILE;
use crate::write::MAGIC;
use crate::write::TAG_ABORT;
use crate::write::TAG_DATA;
use crate::write::TAG_END;
use crate::write::TAG_ENTRY;
use crate::write::TAG_FINISH;
use crate::ArchiveError;
use crate::ExtractLimits;
use crate::Selection;
use std::fs::File;
use std::io::Write;
use std::os::unix::io::AsRawFd;
use std::path::Path;

/// Builds an `FZW1` byte stream frame by hand -- see `write.rs`'s own module doc for the grammar.
#[derive(Default)]
struct FrameBuilder {
    bytes: Vec<u8>,
    started: bool,
}

impl FrameBuilder {
    fn new() -> Self {
        let mut builder = FrameBuilder::default();
        builder.bytes.extend_from_slice(MAGIC);
        builder.started = true;
        builder
    }

    /// No magic at all -- for the "empty stream" protocol-violation test.
    fn empty() -> Self {
        FrameBuilder::default()
    }

    fn entry(
        mut self,
        ordinal: u32,
        is_directory: bool,
        size: Option<i64>,
        mtime_ms: i64,
        mode: u32,
        path: &str,
    ) -> Self {
        self.bytes.push(TAG_ENTRY);
        self.bytes.extend_from_slice(&ordinal.to_le_bytes());
        self.bytes.push(if is_directory {
            KIND_DIRECTORY
        } else {
            KIND_FILE
        });
        self.bytes
            .extend_from_slice(&size.unwrap_or(-1).to_le_bytes());
        self.bytes.extend_from_slice(&mtime_ms.to_le_bytes());
        self.bytes.extend_from_slice(&mode.to_le_bytes());
        let path_bytes = path.as_bytes();
        self.bytes
            .extend_from_slice(&(path_bytes.len() as u32).to_le_bytes());
        self.bytes.extend_from_slice(path_bytes);
        self
    }

    fn data(mut self, ordinal: u32, chunk: &[u8]) -> Self {
        self.bytes.push(TAG_DATA);
        self.bytes.extend_from_slice(&ordinal.to_le_bytes());
        self.bytes
            .extend_from_slice(&(chunk.len() as u32).to_le_bytes());
        self.bytes.extend_from_slice(chunk);
        self
    }

    fn end(mut self, ordinal: u32, bytes_written: u64) -> Self {
        self.bytes.push(TAG_END);
        self.bytes.extend_from_slice(&ordinal.to_le_bytes());
        self.bytes.extend_from_slice(&bytes_written.to_le_bytes());
        self
    }

    fn finish(mut self) -> Vec<u8> {
        self.bytes.push(TAG_FINISH);
        self.bytes
    }

    fn abort(mut self, message: &str) -> Vec<u8> {
        self.bytes.push(TAG_ABORT);
        let message_bytes = message.as_bytes();
        self.bytes
            .extend_from_slice(&(message_bytes.len() as u16).to_le_bytes());
        self.bytes.extend_from_slice(message_bytes);
        self.bytes
    }

    /// Stops without `FINISH`/`ABORT` -- a feeder that died mid-stream.
    fn truncated(self) -> Vec<u8> {
        self.bytes
    }
}

fn write_zip(frames: Vec<u8>, level: u32) -> Result<crate::write::WriteReport, ArchiveError> {
    write_frames_io(
        frames.as_slice(),
        Vec::new(),
        &FormatOptions {
            format: WriteFormat::Zip,
            level,
        },
    )
}

/// [write_frames_io] into an owned `Vec<u8>`, handed back alongside the report so a test can both
/// assert on the outcome and read the bytes back through this crate's own reader.
fn write_into_vec(
    frames: Vec<u8>,
    options: &FormatOptions,
) -> (Result<crate::write::WriteReport, ArchiveError>, Vec<u8>) {
    let mut output = Vec::new();
    let result = write_frames_io(frames.as_slice(), &mut output, options);
    (result, output)
}

fn write_to_temp(dir: &Path, name: &str, bytes: &[u8]) -> File {
    let path = dir.join(name);
    File::create(&path).unwrap().write_all(bytes).unwrap();
    File::open(&path).unwrap()
}

#[test]
fn zip_round_trips_files_directories_and_nested_paths() {
    let frames = FrameBuilder::new()
        .entry(0, true, None, 1_577_836_800_000, 0o755, "docs/")
        .entry(
            1,
            false,
            Some(11),
            1_577_836_800_000,
            0o644,
            "docs/hello.txt",
        )
        .data(1, b"hello world")
        .end(1, 11)
        .entry(2, false, Some(0), 1_577_836_800_000, 0o644, "empty.txt")
        .end(2, 0)
        .finish();
    let (result, bytes) = write_into_vec(
        frames,
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 6,
        },
    );
    let report = result.unwrap();
    assert_eq!(report.entries, 3);
    assert_eq!(report.bytes_in, 11);
    assert_eq!(report.bytes_out, bytes.len() as u64);
    assert!(!bytes.is_empty());

    let dir = tempdir();
    let file = write_to_temp(dir.path(), "created.zip", &bytes);
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    let mut paths: Vec<_> = inspection.entries.iter().map(|e| e.path.clone()).collect();
    paths.sort();
    assert_eq!(paths, vec!["docs/", "docs/hello.txt", "empty.txt"]);

    let file = write_to_temp(dir.path(), "created2.zip", &bytes);
    let mut provider = TempProvider::new(dir.path());
    extract(
        file.as_raw_fd(),
        &Selection::All,
        &ExtractLimits::default(),
        &mut provider,
    )
    .unwrap();
    assert_eq!(provider.read("docs/hello.txt"), b"hello world");
    assert_eq!(provider.read("empty.txt"), b"");
}

#[test]
fn every_format_and_level_tier_round_trips_the_same_content() {
    let cases: &[(WriteFormat, &[u32])] = &[
        (WriteFormat::Zip, &[1, 6, 9]),
        (WriteFormat::TarGz, &[1, 6, 9]),
        (WriteFormat::TarXz, &[1, 6]),
        (WriteFormat::TarZstd, &[1, 3, 19]),
        (WriteFormat::TarBzip2, &[1, 9]),
        (WriteFormat::TarLz4, &[1, 3, 9]),
    ];
    for (format, levels) in cases {
        for &level in *levels {
            let content =
                b"the quick brown fox jumps over the lazy dog, repeated for compressibility. "
                    .repeat(64);
            let frames = FrameBuilder::new()
                .entry(
                    0,
                    false,
                    Some(content.len() as i64),
                    1_600_000_000_000,
                    0o644,
                    "payload.bin",
                )
                .data(0, &content)
                .end(0, content.len() as u64)
                .finish();
            let (result, bytes) = write_into_vec(
                frames,
                &FormatOptions {
                    format: *format,
                    level,
                },
            );
            let report = result.unwrap_or_else(|e| panic!("{format:?} level {level} failed: {e}"));
            assert_eq!(report.entries, 1, "{format:?} level {level}");
            assert_eq!(
                report.bytes_in,
                content.len() as u64,
                "{format:?} level {level}"
            );

            let dir = tempdir();
            let file = write_to_temp(dir.path(), "created.out", &bytes);
            let mut provider = TempProvider::new(dir.path());
            extract(
                file.as_raw_fd(),
                &Selection::All,
                &ExtractLimits::default(),
                &mut provider,
            )
            .unwrap_or_else(|e| panic!("{format:?} level {level} did not read back: {e}"));
            assert_eq!(
                provider.read("payload.bin"),
                content,
                "{format:?} level {level}"
            );
        }
    }
}

#[test]
fn a_higher_level_compresses_smaller_than_store_on_compressible_content() {
    let content = b"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".repeat(64);
    let frame_for = |content: &[u8]| {
        FrameBuilder::new()
            .entry(0, false, Some(content.len() as i64), 0, 0o644, "a.bin")
            .data(0, content)
            .end(0, content.len() as u64)
            .finish()
    };
    let (stored, _) = write_into_vec(
        frame_for(&content),
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 0,
        },
    );
    let stored = stored.unwrap();
    let (compressed, _) = write_into_vec(
        frame_for(&content),
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 9,
        },
    );
    let compressed = compressed.unwrap();
    assert!(
        compressed.bytes_out < stored.bytes_out,
        "compressed {} was not smaller than stored {}",
        compressed.bytes_out,
        stored.bytes_out
    );
}

#[test]
fn unicode_names_are_written_on_a_thread_the_test_never_pins_the_locale_of() {
    // A freshly spawned thread starts with no `uselocale` pin of its own (the C/POSIX global
    // locale) -- proving the *engine's* own pin in `write_frames_io` is what makes this work,
    // not something this test arranged ambiently for every thread in the process. Without the
    // pin, `zip:hdrcharset=UTF-8` fails the header outright under the C locale (the design's own
    // review finding) -- this is a write failure to catch, not mojibake to tolerate.
    let name = "caf\u{e9}/\u{6587}\u{4ef6}.txt";
    let handle = std::thread::spawn(move || {
        let frames = FrameBuilder::new()
            .entry(0, false, Some(0), 0, 0o644, name)
            .end(0, 0)
            .finish();
        write_into_vec(
            frames,
            &FormatOptions {
                format: WriteFormat::Zip,
                level: 6,
            },
        )
    });
    let (result, bytes) = handle.join().unwrap();
    result.unwrap();

    // A byte-level check that needs no locale of its own: the UTF-8 encoded name is present
    // verbatim (not CP437/Latin-1 mojibake), and the ZIP local file header's general-purpose bit
    // 11 (the UTF-8-name flag `zip.c` sets when the writer's locale reported UTF-8) is set.
    let name_bytes = name.as_bytes();
    assert!(
        bytes
            .windows(name_bytes.len())
            .any(|window| window == name_bytes),
        "the UTF-8 encoded pathname was not found verbatim in the written archive"
    );
    let signature = b"PK\x03\x04";
    let header_at = bytes
        .windows(4)
        .position(|window| window == signature)
        .expect("no ZIP local file header signature in the written archive");
    let flags = u16::from_le_bytes([bytes[header_at + 6], bytes[header_at + 7]]);
    assert_ne!(
        flags & 0x0800,
        0,
        "the UTF-8 name flag (bit 11) was not set"
    );

    // And the full round trip through this crate's own reader, on a thread this test pins for
    // exactly that read (the read side's own locale sensitivity, on the glibc host, is a
    // pre-existing fact of `archive_entry`'s lazy MBS/WCS conversion -- not this module's own
    // claim, and not what this test exists to prove; the byte-level check above already did).
    let dir = tempdir();
    let file = write_to_temp(dir.path(), "unicode.zip", &bytes);
    let inspection =
        crate::write::with_c_utf8_locale_for_test(|| inspect(file.as_raw_fd(), &Limits::default()))
            .unwrap();
    assert_eq!(inspection.entries.len(), 1);
    assert_eq!(inspection.entries[0].path, name);
    assert!(!inspection.entries[0].name_lossy);
}

#[test]
fn zip_tolerates_an_unknown_size_and_records_the_true_byte_count() {
    let frames = FrameBuilder::new()
        .entry(0, false, None, 0, 0o644, "streamed.bin")
        .data(0, b"twelve bytes")
        .end(0, 12)
        .finish();
    let (result, bytes) = write_into_vec(
        frames,
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 6,
        },
    );
    result.unwrap();
    let dir = tempdir();
    let file = write_to_temp(dir.path(), "unknown-size.zip", &bytes);
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(inspection.entries[0].uncompressed, Some(12));
}

#[test]
fn tar_refuses_an_entry_of_unknown_size() {
    let frames = FrameBuilder::new()
        .entry(0, false, None, 0, 0o644, "streamed.bin")
        .data(0, b"twelve bytes")
        .end(0, 12)
        .finish();
    let result = write_zip(frames.clone(), 6); // sanity: zip accepts it (proven above too)
    assert!(result.is_ok());
    let result = write_frames_io(
        frames.as_slice(),
        Vec::new(),
        &FormatOptions {
            format: WriteFormat::TarGz,
            level: 6,
        },
    );
    assert!(
        matches!(result, Err(ArchiveError::Failed(ref m)) if m.contains("size to be known")),
        "{result:?}"
    );
}

#[test]
fn a_shortfall_against_the_declared_size_is_benign_for_zip() {
    // The manifest's plan-time estimate said 10 bytes; only 3 were actually available at feed
    // time (design section 1's own example) -- END says what was truly sent (3), not what was
    // declared, and that is not a protocol violation.
    let frames = FrameBuilder::new()
        .entry(0, false, Some(10), 0, 0o644, "shrunk.bin")
        .data(0, b"abc")
        .end(0, 3)
        .finish();
    let (result, bytes) = write_into_vec(
        frames,
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 6,
        },
    );
    result.unwrap();
    let dir = tempdir();
    let file = write_to_temp(dir.path(), "shortfall.zip", &bytes);
    let inspection = inspect(file.as_raw_fd(), &Limits::default()).unwrap();
    assert_eq!(
        inspection.entries[0].uncompressed,
        Some(3),
        "the true count, not the declared one"
    );
}

#[test]
fn a_byte_count_exceeding_the_declared_size_aborts_the_whole_write() {
    let frames = FrameBuilder::new()
        .entry(0, false, Some(5), 0, 0o644, "grew.bin")
        .data(0, b"abc")
        .data(0, b"def") // 3 + 3 = 6 > the declared 5
        .end(0, 6)
        .finish();
    let result = write_frames_io(
        frames.as_slice(),
        Vec::new(),
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 6,
        },
    );
    assert!(
        matches!(result, Err(ArchiveError::Failed(ref m)) if m.contains("declared size")),
        "{result:?}"
    );
}

#[test]
fn abort_after_a_finished_entry_poisons_the_writer_and_the_output_does_not_parse() {
    let frames = FrameBuilder::new()
        .entry(0, false, Some(5), 0, 0o644, "one.bin")
        .data(0, b"hello")
        .end(0, 5)
        .abort("the app process cancelled");
    let (result, bytes) = write_into_vec(
        frames,
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 6,
        },
    );
    assert!(matches!(result, Err(ArchiveError::Cancelled)), "{result:?}");
    assert!(
        !bytes.is_empty(),
        "the first entry's own bytes were still flushed before the abort"
    );

    let dir = tempdir();
    let file = write_to_temp(dir.path(), "aborted.zip", &bytes);
    // The whole point of poisoning: `archive_write_free` without a successful `close` must NOT
    // quietly emit a well-formed archive holding entry 0 and nothing else -- proven, not assumed.
    assert!(
        inspect(file.as_raw_fd(), &Limits::default()).is_err(),
        "an aborted write's output parsed as a valid archive"
    );
}

#[test]
fn protocol_violations_are_reported_never_panicked() {
    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("empty stream", FrameBuilder::empty().finish()),
        ("wrong magic", {
            let mut bytes = b"XXXX".to_vec();
            bytes.push(TAG_FINISH);
            bytes
        }),
        ("unknown frame tag", {
            let mut bytes = FrameBuilder::new().truncated();
            bytes.push(0xEE);
            bytes
        }),
        (
            "data with no open entry",
            FrameBuilder::new().data(0, b"x").finish(),
        ),
        (
            "finish mid entry",
            FrameBuilder::new()
                .entry(0, false, Some(1), 0, 0o644, "a")
                .finish(),
        ),
        (
            "abort while an entry is open",
            FrameBuilder::new()
                .entry(0, false, Some(1), 0, 0o644, "a")
                .abort("cancelled mid-entry"),
        ),
        (
            "end names the wrong ordinal",
            FrameBuilder::new()
                .entry(0, false, Some(1), 0, 0o644, "a")
                .data(0, b"x")
                .end(1, 1)
                .finish(),
        ),
        (
            "end byte count disagrees with what was sent",
            FrameBuilder::new()
                .entry(0, false, Some(5), 0, 0o644, "a")
                .data(0, b"abc")
                .end(0, 99)
                .finish(),
        ),
        (
            "non increasing ordinal",
            FrameBuilder::new()
                .entry(1, true, None, 0, 0o755, "a/")
                .entry(1, true, None, 0, 0o755, "b/")
                .finish(),
        ),
        ("unknown entry kind", {
            let mut bytes = FrameBuilder::new().truncated();
            bytes.push(TAG_ENTRY);
            bytes.extend_from_slice(&0u32.to_le_bytes());
            bytes.push(9); // neither KIND_FILE nor KIND_DIRECTORY
            bytes.extend_from_slice(&(-1i64).to_le_bytes());
            bytes.extend_from_slice(&0i64.to_le_bytes());
            bytes.extend_from_slice(&0o644u32.to_le_bytes());
            bytes.extend_from_slice(&1u32.to_le_bytes());
            bytes.push(b'a');
            bytes
        }),
        (
            "truncated stream (no END, no FINISH)",
            FrameBuilder::new()
                .entry(0, false, Some(5), 0, 0o644, "a")
                .truncated(),
        ),
    ];
    for (name, frames) in cases {
        let result = write_frames_io(
            frames.as_slice(),
            Vec::new(),
            &FormatOptions {
                format: WriteFormat::Zip,
                level: 6,
            },
        );
        assert!(
            matches!(
                result,
                Err(ArchiveError::Failed(_)) | Err(ArchiveError::Cancelled)
            ),
            "{name}: {result:?}"
        );
    }
}

#[test]
fn cancellation_via_a_closed_out_fd_is_reported_as_cancelled() {
    // A pipe whose reader is gone: the archive write's own bytes eventually meet EPIPE once the
    // kernel pipe buffer (typically 64 KiB) fills.
    let (reader, writer) = std::io::pipe().unwrap();
    drop(reader);
    let dir = tempdir();
    let content = vec![b'x'; 512 * 1024];
    let frames = FrameBuilder::new()
        .entry(0, false, Some(content.len() as i64), 0, 0o644, "big.bin")
        .data(0, &content[..500_000])
        .data(0, &content[500_000..])
        .end(0, content.len() as u64)
        .finish();
    let input_file = write_to_temp(dir.path(), "in.fzw", &frames);
    let result = write_frames(
        input_file.as_raw_fd(),
        writer.as_raw_fd(),
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 0,
        },
    );
    assert!(matches!(result, Err(ArchiveError::Cancelled)), "{result:?}");
}

/// A committed golden ZIP (design section 2.8): fixed mtimes, mode, level 6, the locale pinned,
/// no uid/gid (ZIP has none to carry) -- a byte-exact regression net against writer drift, the
/// write-side mirror of `fylz-ffi-android`'s own `tree.fzx` golden for extraction. Every name is
/// ASCII on purpose: ZIP's UTF-8 name flag (bit 11) is only ever set for a *non-ASCII* pathname
/// (`SURVEY-M35-CREATE.md` section 2.2), so this fixture's bytes stay identical whether or not
/// the ambient locale happens to already be UTF-8, and drift here can only mean the writer's
/// output genuinely changed. `FYLZ_WRITE_GOLDEN=1` (re)writes it; without it the committed bytes
/// must match exactly.
#[test]
fn golden_zip_matches_the_committed_created_fixture() {
    let write_golden = std::env::var_os("FYLZ_WRITE_GOLDEN").is_some_and(|v| v == "1");
    let frames = FrameBuilder::new()
        .entry(0, true, None, 1_577_836_800_000, 0o755, "docs/")
        .entry(1, true, None, 1_577_836_800_000, 0o755, "docs/notes/")
        .entry(
            2,
            false,
            Some(11),
            1_577_836_800_000,
            0o644,
            "docs/hello.txt",
        )
        .data(2, b"hello world")
        .end(2, 11)
        .entry(
            3,
            false,
            Some(23),
            1_577_836_800_000,
            0o644,
            "docs/notes/second.txt",
        )
        .data(3, b"more data, a bit longer")
        .end(3, 23)
        .entry(4, false, Some(0), 1_577_836_800_000, 0o644, "empty.txt")
        .end(4, 0)
        .finish();
    let (result, bytes) = write_into_vec(
        frames,
        &FormatOptions {
            format: WriteFormat::Zip,
            level: 6,
        },
    );
    result.unwrap();

    let path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/created/tree.zip.created");
    if write_golden {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        eprintln!("wrote {} ({} bytes)", path.display(), bytes.len());
        return;
    }
    let committed = std::fs::read(&path).unwrap_or_else(|e| {
        panic!(
            "{}: {e} (run `FYLZ_WRITE_GOLDEN=1 cargo test -p fylz-archive golden` to write it)",
            path.display()
        )
    });
    assert!(
        committed == bytes,
        "the writer no longer reproduces the committed {} ({} vs {} bytes); regenerate it with \
         FYLZ_WRITE_GOLDEN=1 once the drift is understood",
        path.display(),
        committed.len(),
        bytes.len()
    );
}
