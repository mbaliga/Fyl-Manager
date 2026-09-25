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

Needs a nightly toolchain (`rustup toolchain install nightly`) and `cargo install cargo-fuzz` --
neither is part of `core/rust-toolchain.toml`'s pinned stable channel, since nothing else in this
workspace needs nightly. This directory is its own Cargo workspace, so `cargo test --workspace`
from `core/` never compiles it: `cargo +nightly fuzz build` is part of every gate that touches the
types a target derives over. Run locally, from `core/` or from here, with:

```
cargo +nightly fuzz build
cargo +nightly fuzz run sniff -- -max_total_time=60
cargo +nightly fuzz run policy_evaluate -- -max_total_time=60
```

CI (`android.yml`) runs the same commands as a smoke test on every push -- 60 seconds per target
is long enough to catch an obvious crash, not a substitute for a real, longer local/scheduled run
before a release.
