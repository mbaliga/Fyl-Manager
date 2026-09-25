# Fuzz targets

Every native parser entry point gets a `cargo fuzz` target, run for at least 60 s per milestone
per `docs/agent/MASTER_PLAN.md` section 3.4.

- **`sniff`** (M2.6) — `fylz_sniff::sniff`/`sniff_with_tail`, fuzzed together from one corpus
  (the input is split in half to feed both the header-only and header+tail entry points). This is
  `fylz-sniff`'s (M2.5) whole public surface: raw, fully attacker-controlled bytes in, an `Option`
  out, never a panic.
- **`policy_evaluate`** (M3.1 part 3a) — `fylz_archive::policy::evaluate`, the extraction policy,
  over `arbitrary`-derived entry tables (names, kinds, link targets, declared sizes) and limit
  tuples; pure Rust, must never panic, and a refusal must always carry its reason. The target
  mirrors the engine's `EntryMetadata`/`Limits` field for field, so a type change on the engine
  side fails `cargo +nightly fuzz build` rather than silently narrowing what is fuzzed.
- **`archive_entries`** (M3.1 part 3b; M3.3a added the browsing pair) — the libarchive-backed
  engine itself: the input is written to a scratch file (the engine takes only a seekable
  descriptor) and fed to `fylz_archive::inspect` (one header pass), `inspect_into` (the same pass
  with the listing codec writer attached, into memory -- also on inputs `inspect` refuses, since
  damage after the first entry is a partial listing there), `read_entry` of the first listed path
  (only when its declared size is modest -- it buffers in memory), `extract_entry_at` of the first
  listed entry by its ordinal into `/dev/null`, `extract` of everything into `/dev/null` under
  tight `ExtractLimits`, and (M3.4a) the bulk pair over a `Selection::Ranges` covering the first
  half of the listed ordinals: `inspect_for_extraction` plus `policy::evaluate_selection`, then
  `extract_blocks` into a counting `BlockSink` (the header-level `FAILED` rule, the CRC/size/decode
  classification and the early exit run here). Seed it with the committed fixtures: pass `../fixtures` (from here)
  or `fixtures` (from `core/`) as a second corpus directory, as the commands below do. libFuzzer
  reads a seed directory recursively, so that one argument also covers M3.2a's ZIP/7z/ISO fixtures
  under `fixtures/archives/` and the hostile ones under `fixtures/archives/hostile/` (150 seed
  files in all with the corpus this run kept). The corpus directory itself is gitignored -- the
  committed fixtures are the seeds, and the commands below are how they reach the fuzzer.
  **Limitation:** libarchive and its companions are built by cmake, not by rustc, so they carry no
  sanitizer instrumentation -- a C memory-safety bug is caught here only if it faults outright
  (SIGSEGV/SIGABRT), never as an ASan report at the first bad byte.

Needs a nightly toolchain (`rustup toolchain install nightly`) and `cargo install cargo-fuzz` --
neither is part of `core/rust-toolchain.toml`'s pinned stable channel, since nothing else in this
workspace needs nightly. This directory is its own Cargo workspace, so `cargo test --workspace`
from `core/` never compiles it: `cargo +nightly fuzz build` is part of every gate that touches the
types a target derives over. Run locally, from `core/` or from here, with:

```
cargo +nightly fuzz build
cargo +nightly fuzz run sniff -- -max_total_time=60
cargo +nightly fuzz run policy_evaluate -- -max_total_time=60
mkdir -p fuzz/corpus/archive_entries    # `corpus/archive_entries` from inside fuzz/
cargo +nightly fuzz run archive_entries fuzz/corpus/archive_entries fixtures -- -max_total_time=60
```

(libFuzzer writes new inputs to the first corpus directory and reads the rest as seeds; the
`archive_entries` line is written for `core/` -- from inside `fuzz/` use `corpus/archive_entries
../fixtures`.)

CI (`android.yml`) runs the same commands as a smoke test on every push -- 60 seconds per target
is long enough to catch an obvious crash, not a substitute for a real, longer local/scheduled run
before a release.
