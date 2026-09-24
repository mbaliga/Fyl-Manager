# Fuzz targets

Every native parser entry point gets a `cargo fuzz` target, run for at least 60 s per milestone
per `docs/agent/MASTER_PLAN.md` section 3.4.

- **`sniff`** (M2.6) — `fylz_sniff::sniff`/`sniff_with_tail`, fuzzed together from one corpus
  (the input is split in half to feed both the header-only and header+tail entry points). This is
  `fylz-sniff`'s (M2.5) whole public surface: raw, fully attacker-controlled bytes in, an `Option`
  out, never a panic.

Needs a nightly toolchain (`rustup toolchain install nightly`) and `cargo install cargo-fuzz` --
neither is part of `core/rust-toolchain.toml`'s pinned stable channel, since nothing else in this
workspace needs nightly. Run locally with:

```
cargo +nightly fuzz run sniff -- -max_total_time=60
```

CI (M2.6, `android.yml`) runs the same command as a smoke test on every push -- 60 seconds is
long enough to catch an obvious crash, not a substitute for a real, longer local/scheduled run
before a release.
