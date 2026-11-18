#![no_main]

use libfuzzer_sys::fuzz_target;

// fylz-sniff's whole job is parsing bytes an attacker fully controls (a file's own content, via
// DecoderService -- see docs/agent/MASTER_PLAN.md section 4.4). Neither entry point may panic on
// any input, however malformed: an OOB slice index, an integer overflow the checked_add/checked_mul
// calls in fylz-sniff::sniff_disc_image/sniff_zip_container exist specifically to avoid, or any
// other unreachable!()-shaped assumption about well-formed input.
fuzz_target!(|data: &[u8]| {
    let _ = fylz_sniff::sniff(data);

    // Exercise the tail-aware path too (DMG's trailer, VHD's footer) from the same corpus, rather
    // than needing a second fuzz target and a second corpus just to reach sniff_with_tail's few
    // extra lines.
    let mid = data.len() / 2;
    let _ = fylz_sniff::sniff_with_tail(&data[..mid], Some(&data[mid..]));
});
