#![no_main]

use fylz_archive::write::write_frames_io;
use fylz_archive::write::FormatOptions;
use fylz_archive::write::WriteFormat;
use libfuzzer_sys::fuzz_target;

// M3.5a (docs/agent/DESIGN-M35-CREATE.md section 2.8): the write engine's frame parser is fed by
// a trusted feeder (Kotlin's ArchiveCreator, defensive anyway per write.rs's own doc comment),
// but the same MASTER_PLAN section 3.4 rule applies to every native entry point regardless of who
// is expected to call it -- a corrupted pipe, an engine/app version mismatch, or a bug in the
// feeder itself must never panic or hang this code. The `io` entry point, not the fd one (the
// design's own instruction): raw bytes straight in as the whole "FZW1" stream, discarded straight
// out through `std::io::sink()`, so this runs with no real pipe and no growing in-memory buffer --
// fast enough for the fuzz loop's own budget. `store` (level 0) on `zip` keeps the per-iteration
// cost to what the frame data itself declares, never xz's own ~93 MiB encoder setup (kept out of
// this default loop, per the design's own instruction).
fuzz_target!(|data: &[u8]| {
    let options = FormatOptions {
        format: WriteFormat::Zip,
        level: 0,
    };
    let _ = write_frames_io(data, std::io::sink(), &options);
});
