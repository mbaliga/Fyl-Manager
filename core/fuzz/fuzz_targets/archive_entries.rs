#![no_main]

use fylz_archive::extract;
use fylz_archive::extract_blocks;
use fylz_archive::extract_entry_at;
use fylz_archive::inspect;
use fylz_archive::inspect_for_extraction;
use fylz_archive::inspect_into;
use fylz_archive::policy::evaluate_selection;
use fylz_archive::policy::Limits;
use fylz_archive::read_entry;
use fylz_archive::ArchiveEntry;
use fylz_archive::ArchiveError;
use fylz_archive::BlockSink;
use fylz_archive::DestinationProvider;
use fylz_archive::EntryMetadata;
use fylz_archive::ExtractLimits;
use fylz_archive::FailKind;
use fylz_archive::Selection;
use fylz_archive::Warning;
use libfuzzer_sys::fuzz_target;
use std::fs::File;
use std::fs::OpenOptions;
use std::io::Write;
use std::os::fd::AsFd;
use std::os::fd::BorrowedFd;
use std::os::unix::io::AsRawFd;
use std::path::PathBuf;

// fylz-archive's whole job is parsing archives an attacker fully controls, in the isolated
// decoder process (docs/agent/MASTER_PLAN.md section 4.4). This target writes the fuzz input to a
// file (the engine's contract is a seekable descriptor; a pipe is refused before a byte is read)
// and drives every parsing entry point over it: inspect() (one header pass, the policy's input),
// read_entry() of the first listed path (bounded by that entry's declared size, so a declared
// bomb never allocates its claim here), and extract() of everything into /dev/null under tight
// runtime limits (the streaming read_data_block path and its caps), plus M3.3's browsing pair:
// inspect_into() (the same pass with the listing codec writer attached, into memory) and
// extract_entry_at() of the first listed entry by its ordinal into /dev/null. None may panic,
// hang, or -- the real point -- crash inside libarchive.
//
// Limitation to know about: libarchive and its five companions are compiled by cmake in the
// engine's build.rs and are NOT sanitizer-instrumented (cargo-fuzz's -Zsanitizer flags reach
// only the Rust code). A memory-safety bug in the C code therefore shows up here only if it
// actually faults (SIGSEGV/SIGABRT), not as an ASan report at the first bad byte. Also,
// archive_read_support_filter_all registers "program" fallbacks for codecs this build lacks
// (lzop, lrzip, grzip, ...): an input carrying one of their magics makes libarchive try to spawn
// that program, which fails cleanly here (and on Android, where no such program exists) but costs
// a fork/exec, so those inputs are slow rather than interesting.

struct DevNull {
    sink: File,
}

impl DestinationProvider for DevNull {
    fn open(&mut self, _entry: &ArchiveEntry) -> Result<Option<BorrowedFd<'_>>, ArchiveError> {
        Ok(Some(self.sink.as_fd()))
    }

    fn done(&mut self, _entry: &ArchiveEntry, _bytes_written: u64) -> Result<(), ArchiveError> {
        Ok(())
    }
}

fn scratch_path() -> PathBuf {
    std::env::temp_dir().join(format!("fylz-archive-fuzz-{}.bin", std::process::id()))
}

fuzz_target!(|data: &[u8]| {
    let path = scratch_path();
    {
        let mut file = File::create(&path).expect("creating the scratch archive file");
        file.write_all(data).expect("writing the fuzz input");
    }
    let limits = Limits {
        // Small enough that a header claiming millions of entries stops early; the listing bound
        // is a memory cap, not a rule the fuzzer should have to fill.
        max_listing_entries: 4_096,
        ..Limits::default()
    };

    let file = File::open(&path).expect("reopening the scratch archive");
    let Ok(inspection) = inspect(file.as_raw_fd(), &limits) else {
        // Damage after the first entry is a partial listing on the browsing path; run it too.
        let mut sink = Vec::new();
        let _ = inspect_into(
            File::open(&path).expect("reopening the scratch archive").as_raw_fd(),
            &limits,
            &mut sink,
        );
        return;
    };
    let mut sink = Vec::new();
    let _ = inspect_into(
        File::open(&path).expect("reopening the scratch archive").as_raw_fd(),
        &limits,
        &mut sink,
    );

    if let Some(first) = inspection.entries.first() {
        // read_entry buffers the whole body in memory: only worth it when the header's own claim
        // is modest (tar-family readers trust it; ZIP's checks the deflate stream against it).
        if first.uncompressed.is_some_and(|size| size <= 1024 * 1024) {
            let file = File::open(&path).expect("reopening the scratch archive");
            let _ = read_entry(file.as_raw_fd(), &first.path);
        }
    }

    if let Some(first) = inspection.entries.first() {
        let dev_null = OpenOptions::new()
            .write(true)
            .open("/dev/null")
            .expect("opening /dev/null");
        let _ = extract_entry_at(
            File::open(&path).expect("reopening the scratch archive").as_raw_fd(),
            first.ordinal as usize,
            &first.path,
            &ExtractLimits {
                max_file_bytes: 1024 * 1024,
                max_total_uncompressed_bytes: 4 * 1024 * 1024,
                max_entries: 4_096,
                max_path_depth: 64,
                max_name_length: 255,
            },
            dev_null.as_raw_fd(),
        );
    }

    let file = File::open(&path).expect("reopening the scratch archive");
    let mut sink = DevNull {
        sink: OpenOptions::new()
            .write(true)
            .open("/dev/null")
            .expect("opening /dev/null"),
    };
    let extract_limits = ExtractLimits {
        max_file_bytes: 1024 * 1024,
        max_total_uncompressed_bytes: 4 * 1024 * 1024,
        max_entries: 4_096,
        max_path_depth: 64,
        max_name_length: 255,
    };
    let _ = extract(file.as_raw_fd(), &Selection::All, &extract_limits, &mut sink);

    // M3.4: the bulk path over the first half of the listed ordinals (a real plan never selects
    // the root, which inspect() dropped; a hostile range that names it is passed over).
    let last = inspection
        .entries
        .get(inspection.entries.len() / 2)
        .map_or(0, |e| e.ordinal);
    let selection = Selection::Ranges(vec![(0, last)]);
    if let Ok(selected) = inspect_for_extraction(
        File::open(&path).expect("reopening the scratch archive").as_raw_fd(),
        &selection,
        &mut || false,
    ) {
        let _ = evaluate_selection(selected.archive_bytes, &selected.entries, &limits);
    }
    let mut counting = Counting::default();
    let _ = extract_blocks(
        File::open(&path).expect("reopening the scratch archive").as_raw_fd(),
        &selection,
        &extract_limits,
        &mut counting,
    );
});

/// A BlockSink that only counts: the frames a real sink would write are the FFI's business.
#[derive(Default)]
struct Counting {
    begun: u32,
    bytes: u64,
    ended: u32,
    failed: u32,
}

impl BlockSink for Counting {
    fn begin(&mut self, _entry: &EntryMetadata) -> Result<bool, ArchiveError> {
        self.begun += 1;
        Ok(true)
    }

    fn write(&mut self, _ordinal: u32, block: &[u8]) -> Result<(), ArchiveError> {
        self.bytes += block.len() as u64;
        Ok(())
    }

    fn end(&mut self, _ordinal: u32, _bytes: u64, _warning: Option<Warning>) -> Result<(), ArchiveError> {
        self.ended += 1;
        Ok(())
    }

    fn failed(&mut self, _ordinal: u32, _kind: FailKind, _message: &str) -> Result<(), ArchiveError> {
        self.failed += 1;
        Ok(())
    }
}
