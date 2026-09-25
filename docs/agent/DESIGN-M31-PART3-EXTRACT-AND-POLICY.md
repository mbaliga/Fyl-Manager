# M3.1 part 3 design: `extract()` and the extraction policy in Rust

Brief for the third and last increment of M3.1 (`docs/agent/MASTER_PLAN.md` §5: "Expose streaming
`open(fd)`, `entries()`, `read_entry(path)` and `extract(selection, dest_fd_provider)`. Enforce the
existing `ArchiveExtractionPolicy` limits: move the policy logic into Rust and delete the Kotlin copy
once parity tests pass."). Parts 1 and 2 gave `fylz-archive` the read path over libarchive 3.8.9 with
lz4, zstd, zlib, bzip2 and xz. This part adds the policy and streaming extraction, with the one
semantic adaptation §3 explains. Written by the planning model from the code as it stands; the
implementer follows it literally and stops on anything it does not settle.

## 1. What exists today (verified in code)

- `app/src/main/java/io/github/mbaliga/fylz/data/ArchiveExtractionPolicy.kt` (100 lines, pure):
  `ArchiveEntryMetadata(name, directory, compressedBytes, uncompressedBytes)`,
  `ArchiveExtractionLimits` (maxEntries 10 000; maxArchiveBytes 2 GiB; maxFileBytes 1 GiB;
  maxTotalUncompressedBytes 4 GiB; maxCompressionRatio 200.0; maxPathDepth 64; maxNameLength 255),
  `ArchiveExtractionDecision(allowed, reason)`, `evaluate(archiveBytes, entries, limits)`. Rules, in
  order: archive size in `0..maxArchiveBytes`; entry count; per entry — `validatePath` (blank; longer
  than `maxNameLength × maxPathDepth`; NUL; leading `/` or `\`; backslashes normalised to `/`;
  segments after dropping empties non-empty; depth ≤ `maxPathDepth`; no `.`/`..`; segment length ≤
  `maxNameLength`; first segment not `^[A-Za-z]:$`), duplicate `normalizedPathKey` (backslash→slash,
  trailing slashes trimmed, `lowercase(Locale.ROOT)`), negative sizes rejected ("unknown size"),
  file `uncompressedBytes ≤ maxFileBytes`, running total with `Long.MAX_VALUE` overflow guard and
  `≤ maxTotalUncompressedBytes`, and for a non-directory with `uncompressedBytes > 0`:
  `compressedBytes == 0` → "implausibly compressed", `uncompressed/compressed > maxCompressionRatio`
  → "suspicious compression ratio". Every rejection carries the exact reason string.
- Tests: `ArchiveExtractionPolicyTest.kt` (8 cases) and `ArchiveExtractionPolicyFuzzTest.kt` (a
  12-path hostile corpus that must always be rejected; 2 000 seeded random traversal variants,
  `Random(0xF71A)`). These are the parity oracle.
- `ArchiveService.kt` (489 lines, zip4j): `inspectZip` and `extractZip` call `evaluate` on
  `readMetadata(zipFile)` (zip4j `FileHeader`s carry compressed and uncompressed sizes); extraction
  stages the archive to cache, checks `ArchiveSpacePolicy` (StatFs — stays Kotlin), then writes each
  entry through `contentResolver.openOutputStream(target.uri, "w")` — i.e. the destination is a SAF
  document the Kotlin side creates; the engine only ever needs a writable fd per entry.
- `fylz-archive` today: `entries(fd)`, `read_entry(fd, path)` over a hand-written `sys` module (10
  functions), `ArchiveEntry { path, size, is_directory }`, `ArchiveError { Fatal, NonUtf8Path }`.
  Nothing calls it from Kotlin yet (no uniffi surface); `DecoderService` (the isolated `:decoders`
  process) is where untrusted parsing must run (§2.3).

## 2. Decisions

1. **Policy port is behaviour-identical for what libarchive can observe, and adapts one check it
   cannot.** libarchive exposes an entry's *uncompressed* size (`archive_entry_size`, with
   `archive_entry_size_is_set`) but no portable per-entry *compressed* size. zip4j did. A literal
   port would mark `compressedBytes` unknown for every tar/gz/xz/zst/lz4 stream and reject them all
   under the "unknown size" rule. So: `compressed_bytes: Option<u64>`; the per-entry ratio and
   "implausibly compressed" rules apply **only when it is known**; an **archive-level ratio** check
   is added for every format (`Σ declared uncompressed / archive_bytes > max_compression_ratio` →
   rejected, same reason string); and **runtime caps during `extract()`** (§2.3) are the real
   defence against headers that lie. Unknown *uncompressed* size stays a rejection, as today.
   Recorded as a deviation for Madhav's review pass (the Kotlin rule set becomes a strict subset
   for ZIP and a slightly different set for streams) — see §5.
2. **Where the policy runs, and when the Kotlin copy goes.** The Rust policy is the source of truth
   from this commit. The Kotlin copy is **not** deleted in part 3: `ArchiveService` still reads with
   zip4j in the app process, and calling Rust from there would load `libfylz_ffi_android.so` into the
   main process — contradicting REPORT-M2's cold-start argument that the library loads only in
   `:decoders`. The deletion happens in the commit that moves `ArchiveService`'s read path onto the
   Rust engine through `DecoderService` (M3.2/M3.3, before M3.10 removes zip4j). Until then, parity
   is proven at the Rust level by porting every Kotlin test case verbatim (§4), and the Kotlin file
   gains a one-line header pointing at `fylz-archive`'s `policy.rs` as the source of truth.
3. **`extract()` streams into caller-supplied fds and enforces limits while it runs.** Signature:

   ```rust
   pub struct ExtractLimits { pub max_file_bytes: u64, pub max_total_uncompressed_bytes: u64, pub max_entries: usize }
   pub enum Selection { All, Paths(Vec<String>) }
   pub trait DestinationProvider {
       /// Called once per selected entry, in archive order. Return `Ok(None)` to skip it (directories,
       /// or a caller that decides not to write this one). The provider owns the fd's lifetime; the
       /// engine writes and flushes, never closes.
       fn open(&mut self, entry: &ArchiveEntry) -> Result<Option<std::os::fd::BorrowedFd<'_>>, ArchiveError>;
       fn done(&mut self, entry: &ArchiveEntry, bytes_written: u64) -> Result<(), ArchiveError>;
   }
   pub struct ExtractReport { pub entries_written: usize, pub bytes_written: u64, pub skipped: usize }
   pub fn extract(fd: RawFd, selection: &Selection, limits: &ExtractLimits, dest: &mut dyn DestinationProvider) -> Result<ExtractReport, ArchiveError>;
   ```

   Runtime rules: a selected entry whose actual data exceeds `max_file_bytes` **or its own declared
   size** stops the whole extraction with `ArchiveError::LimitExceeded { entry, rule }`; the running
   total is capped at `max_total_uncompressed_bytes`; more than `max_entries` selected entries stops
   it. Output already written stays where it is — the caller (Kotlin's operation engine: staging,
   journal, recycle) owns cleanup, exactly as `extractBounded` does today; `extract()` never deletes.
   `Selection::Paths` matches exact entry paths after the same normalisation the policy uses; a
   requested path absent from the archive is reported in `ExtractReport` (a new `missing: Vec<String>`
   field), not an error. One forward pass; entries not selected are skipped without reading their
   data (libarchive's `archive_read_next_header` skips unread bodies).
4. **`inspect(fd) -> Inspection { archive_bytes, entries: Vec<EntryMetadata> }`** replaces the ad hoc
   `entries()` for policy purposes: `EntryMetadata { path, is_directory, uncompressed: Option<u64>,
   compressed: Option<u64> }` (compressed always `None` in part 3; the field exists so M3.2's
   seekable ZIP reader can fill it). `archive_bytes` comes from `fstat` on the fd. `entries()` stays
   as the simple listing API.
5. **Seekability note for M3.2.** libarchive reads ZIP central directories only through a seekable
   input; `archive_read_open_fd` registers a seek callback for regular files, so today's `open_fd`
   already gets central-directory sizes for on-disk ZIPs. Streams (pipes) fall back to the streaming
   ZIP reader, where sizes may be unset until the data descriptor — that is exactly why M3.2 passes a
   seekable `ParcelFileDescriptor`. Part 3 asserts nothing about pipes.
6. **Fuzzing (§3.4: "every native parser entry point gets a `cargo fuzz` target").** Two targets in
   `core/fuzz/fuzz_targets/`: `policy_evaluate` (arbitrary bytes → a `Vec<EntryMetadata>` and an
   `archive_bytes`; must never panic; pure Rust) and `archive_entries` (arbitrary bytes written to a
   temp file → `inspect()` then `read_entry()` of the first path; a libarchive crash shows up as a
   fuzzer crash even though the C code is not sanitizer-instrumented — record that limitation).
   Run each 60 s locally and record executions/crashes in the PROGRESS row; add both to
   `android.yml`'s existing fuzz smoke step (the M2.6 pattern), 60 s each.

## 3. The policy module (`core/crates/fylz-archive/src/policy.rs`)

```rust
pub struct Limits { max_entries: usize, max_archive_bytes: u64, max_file_bytes: u64,
                    max_total_uncompressed_bytes: u64, max_compression_ratio: f64,
                    max_path_depth: usize, max_name_length: usize }   // Default = the Kotlin defaults
pub struct EntryMetadata { path: String, is_directory: bool, compressed: Option<u64>, uncompressed: Option<u64> }
pub struct Decision { allowed: bool, reason: Option<&'static str> }  // reason strings identical to Kotlin's
pub fn evaluate(archive_bytes: u64, entries: &[EntryMetadata], limits: &Limits) -> Decision
```

Rule order and reason strings are copied from the Kotlin file verbatim, with these mappings:
`archiveBytes < 0` → not representable (`u64`); `Long.MAX_VALUE` overflow → `checked_add`;
`lowercase(Locale.ROOT)` → `str::to_lowercase()` (both locale-independent Unicode case mapping;
note the equivalence in a one-line comment); the drive-letter regex → an explicit two-byte check;
`uncompressed == None` → "Archive contains an entry with unknown size." (as today for negatives);
`compressed == None` → the per-entry ratio rules are skipped for that entry (decision 1); after the
loop, the archive-level ratio check (decision 1) with the existing "suspicious compression ratio"
reason. `validate_path` and `normalized_path_key` are private, as in Kotlin.

## 4. Tests (all in `fylz-archive`; Kotlin tests untouched)

1. `policy_tests.rs`: the 8 `ArchiveExtractionPolicyTest` cases ported one-to-one — same inputs,
   same expected `allowed`/`reason` — plus the 12-path hostile corpus and the 2 000 seeded traversal
   variants from `ArchiveExtractionPolicyFuzzTest` (use a small in-test LCG seeded with `0xF71A`;
   no `rand` dependency; the variants need not be bit-identical to Kotlin's `Random`, only the same
   construction rule and count). Where a Kotlin case depends on `compressedBytes` (ratio,
   implausibly-compressed), keep it with `Some(...)` AND add its `None` twin asserting the entry is
   accepted by the per-entry rule and caught by the archive-level rule when the sum warrants.
2. `extract_tests.rs`: with the existing `ar` fixture builder and part 2's `core/fixtures/*.tar.*`:
   `Selection::All` writes every member byte-exact into temp files via a test `DestinationProvider`;
   `Selection::Paths` writes only the named ones and reports a missing path; directories are offered
   and skipped; `max_file_bytes` smaller than a member stops with `LimitExceeded` naming it and the
   earlier members are intact; `max_total_uncompressed_bytes` likewise; `max_entries` likewise; a
   hostile fixture (part 2's truncated stream) surfaces as `Fatal`, never a panic; `inspect()` on a
   tar reports `uncompressed: Some(..)`, `compressed: None`, and `archive_bytes` equal to the file
   length.
3. Fuzz targets as in §2.6, with the 60 s results recorded.

## 5. Sequencing, gate, and what to record

- Two commits: `M3.1 part 3a: extraction policy in Rust (policy.rs), parity tests, fuzz target` and
  `M3.1 part 3b: streaming extract() with runtime limits, inspect(), fuzz target, CI smoke`.
- Gate per commit: `cargo build/test -p fylz-archive`, `cargo clippy --workspace --all-targets -- -D
  warnings`, `cargo fmt --check`, `cargo deny check`, `cargo +nightly fuzz run <target> --
  -max_total_time=60` for the new target(s), the three-ABI `cargo ndk` build, and `./gradlew
  --no-daemon :app:assembleDebug` (nothing in `app/` changes except the one-line header in
  `ArchiveExtractionPolicy.kt`, so `:app:testDebugUnitTest` is unaffected — run it once in 3a anyway).
- `PROGRESS.md`: `M3.1 (part 3)` row; the Kotlin-deletion is explicitly deferred to the M3.2/M3.3
  read-path move with the reason in decision 2. `REVIEW_QUEUE.md`: the ratio-rule adaptation
  (decision 1) as an item for Madhav's pass — it is a behaviour change for non-ZIP archives once the
  Rust engine is live. `DEVICE_CHECKS.md`: none new (no device-visible change yet).
- `THIRD_PARTY_NOTICES.md`: unchanged (no new dependency; `arbitrary`/`libfuzzer-sys` already
  serve `core/fuzz`).
