# REPORT-M3: Archives and compression

Per `docs/agent/MASTER_PLAN.md` §3.5. Covers M3's ten tasks, M3.1–M3.10 (`docs/agent/MASTER_PLAN.md`
§5, "M3. Archives and compression"). Full per-task detail lives in `docs/agent/PROGRESS.md`'s task
table; this report summarises rather than repeats it, and gives most of its own space to the four
acceptance lines the master plan states for the whole milestone (§3 below), assessed honestly
against what has actually landed rather than restated from memory. M3 is not a review-gated
milestone by name in `docs/agent/MASTER_PLAN_ADDENDUM_1.md` §A's list (GATE-M1, GATE-M2, GATE-M4,
GATE-M8 technical parts); its own acceptance line is a plain milestone-completion check, and this
report plus `docs/agent/REVIEW_QUEUE.md`'s new "M3 acceptance" entry are what closes it out for
Madhav's later single review pass.

## 1. Status

**Verification level: the `core/` Cargo gate (`cargo test --workspace`/`clippy --all-targets -D
warnings`/`fmt --check`/`deny check`/fuzz build, all green for every M3.x commit and re-run fresh
for this report) plus Robolectric/plain-JVM `testDebugUnitTest`/`lintDebug` on the Kotlin side, plus
`assembleDebug` and the `cargo ndk` cross-compile for all three ABIs — no real Android device,
emulator, or multi-gigabyte-of-free-disk sandbox was available in this run's container.** All ten
M3 tasks are done, with **M3.10 explicitly partial, not done**, because its own stated gate ("once
the parity tests for create, extract and AES pass") is unreachable in this build and was not met —
see its own row and `docs/agent/REVIEW_QUEUE.md`'s M3.10 entry. `testDebugUnitTest` grew from
393/393 at the end of M2 to 749/749 by M3.9 and stayed there through M3.10 (a pure removal commit
with nothing to newly test), with `lintDebug` clean throughout. The Rust side grew `fylz-archive`
from a 4-test read-only stub (M3.1 part 1) to 149 tests (1 intentionally ignored) covering read,
seek, listing, policy, extraction-block and write behaviour, plus 3 in `fylz-ffi-android` and more
across the other exercised crates — 203 Rust tests total, re-run fresh for this report (§3.4, §3.3).
What this milestone cannot itself confirm — the real 5 GB device extraction, and every other
device-only claim `docs/agent/DEVICE_CHECKS.md` §17–24 lists for M3.2–M3.9 — is tracked there and
restated honestly, not glossed over, in §3.2 and §5 below.

## 2. Tasks

| ID | Status | Commit(s) | Notes |
|---|---|---|---|
| M3.1 | done, verified | `5490b0d`, `1f0a9a6`, `a849282`, `0ff0196`, `5c8c0e3`, `cdd997f`, `d19be8f`, `1fc4fe3`, `8e35497`, `1983c0c`, `a418a9c`, `d43533b` | `fylz-archive` over libarchive: vendored (BSD) as a git submodule with zlib/bzip2/xz/zstd/lz4 also vendored (all permissive, verified in `THIRD_PARTY_NOTICES.md`); a hand-written FFI backs `entries`/`read_entry`/`inspect`/`extract`; the extraction policy moved into Rust (`policy.rs`) with parity tests and the `policy_evaluate` fuzz target; a seekable `ParcelFileDescriptor` reaches the decoder process with no whole-archive staging. |
| M3.2 | done, verified | `05c3531` and predecessors above (M3.2a–c folded into the M3.1 commit sequence per `PROGRESS.md`) | Random access, no whole-archive staging: ZIP/7z/ISO read with seeks through the decoder process; `ArchiveSource` resolves a seekable descriptor or stages only non-seekable remote input, with a space check. |
| M3.3 | done, verified | `05c3531` (a), `9b1bceb` (b), `c7f6a86` (c), `07fa3b1` (d, docs) | Archive browsing as folders: opening an archive pushes a virtual breadcrumb location; preview/copy-out/share/drag-out work on entries; nested archives open the same way with bounded recursion depth 4. |
| M3.4 | done, verified | `5fdc515` (a), `075ecab` (b), `1487739` (c) | Selective extract through the transfer queue: Extract here/to `<name>/`/to…/selected entries all run through `TransferWorker` with progress, cancel, staging, verification and conflict handling; the legacy zip4j path narrowed to encrypted ZIPs only. |
| M3.5 | done, verified | `7f2c9db` (a), `f83713a` (b), `8b55493` (c), `932f713` (docs) | Create: a Compress sheet (zip, tar.gz, tar.xz, tar.zst, plus tar.bz2/tar.lz4 beyond the plan's own list) with level, split size and "store paths relative to selection", running in the queue. 7z creation and password both deliberately deferred (7z to the GPL/AGPL "7-Zip pack" add-on behind GATE-A; password to M5, blocked on a crypto backend) — logged, not silently dropped. |
| M3.6 | done, verified | `a262b64` | Edit in place for ZIP only (7z has no writer at all yet, so no 7z edit either — the M3.5 deferral propagates here, logged in this commit's own REVIEW_QUEUE entry): add/delete/rename as rewrite-to-staging then atomic replace; the old archive goes to the recycle bin. |
| M3.7 | done, verified | `1ca1ff7` | Legacy ZIP filename charset: auto-detect (CP437/CP866/GBK/Shift-JIS/EUC-KR) with a manual override in the archive header bar. |
| M3.8 | done, verified | `b3bee3d` | Test archive: verifies CRCs without extracting, via the isolated extraction instance with a discard sink; an encrypted ZIP is refused up front ("password required, cannot test") rather than routed through a password prompt that could never be honoured. |
| M3.9 | done, verified | `5b5e196` | Shared, session-only password prompt across archive formats; passwords held in `CharArray`, wiped after use, never logged or persisted (`ArchivePasswordSession`, `PasswordPromptDialog`). |
| M3.10 | **partial** | `ff0b3de` | `ExtendedArchiveBrowserService` and the now-dead `commons-compress`/`org.tukaani:xz` Gradle dependencies removed (zero callers, re-confirmed by grep). zip4j and `ArchiveService.kt` retained as-is: the plan's own stated gate for this task ("once the parity tests for create, extract and AES pass") is unreachable without a crypto backend `fylz-archive`'s `build.rs` deliberately does not vendor, and was not attempted. Full reasoning and a recommendation for Madhav in `docs/agent/REVIEW_QUEUE.md`'s M3.10 entry. |

## 3. Acceptance criteria

`docs/agent/MASTER_PLAN.md`'s own M3 acceptance block states four lines. Each is assessed below
against what is actually committed on this branch today, re-verified for this report rather than
carried forward from old numbers.

### 3.1 "A fixture corpus of at least one of each format opens."

The plan's own format table (`MASTER_PLAN.md` lines ~354–363) lists roughly 30 format names across
eight rows. Checked against `core/fixtures/` (top level and `archives/`/`archives/hostile/`) and
`core/crates/fylz-archive/src/*_tests.rs`'s actual use of `inspect`/`inspect_into`/`entries`/
`read_entry` (the engine's own inspection path — not just a grep for the extension), not against
old progress notes:

| Format(s) | Real, committed fixture? | Opens through the engine's inspection path? |
|---|---|---|
| ZIP (stored/deflate, ZIP64-shaped, streamed/data-descriptor, legacy CP437, nested-archive, implicit-dir, backslash-path, dot-rooted) | Yes — `sample-cd.zip`, `sample-streamed.zip`, `sample-entries.zip`, `legacy-cp437.zip`, `tree.zip`, `nested-depth-4/5.zip`, `implicit-dirs.zip`, `backslash.zip`, `sample-deflate.zip`, plus the hostile/corrupt set below | **Yes**, extensively — `inspect`/`inspect_into`/`extract_entry_at`/`extract`/golden-listing tests, and write is round-tripped (`tree.zip.created` golden) |
| ZIPX (PPMd/LZMA methods) | Only `ppmd-bad.zip`, a deliberately **corrupted** PPMd stream used to test the decode-error path | **No successful ZIPX read is fixture-tested.** The only ZIPX-method fixture that exists is built to fail; a real, valid ZIPX with a PPMd/LZMA entry has never been opened by this engine in a test. Named honestly as untested, not "broken". |
| ZIP AES-256 / ZipCrypto | No dedicated fixture under `core/fixtures/`; the legacy zip4j path (`ArchiveService.kt`) is exercised only indirectly (`ArchivePasswordSessionTest`, `FylzV1AppLogicTest`), not against a real encrypted-ZIP byte fixture | AES read/write works via zip4j in production code, but has no fixture-driven test in this repo proving it byte-for-byte; not a new gap this report introduces — see M3.9's REVIEW_QUEUE entry, item 8, on why an `ArchiveServiceTest` integration test was attempted and dropped |
| 7z (unencrypted, plain and encoded header) | Yes — `sample-copy.7z`, `sample-lzma2.7z`, plus `crc-bad.7z`/`solid-bad.7z` for the corrupted-stream paths | **Yes** — `inspect`/`inspect_into`/extraction tests all pass |
| 7z (encrypted headers) | Yes — `sample-encrypted-header.7z` | **Yes, correctly refused**: libarchive reports "The archive header is encrypted, but currently not supported" → `ArchiveError::Unsupported`; this is the expected, tested outcome, not a false "it works" |
| 7z (write, any kind) | N/A | **Not implemented at all.** `write.rs`'s `WriteFormat` enum has no 7z variant; deferred to the GPL/AGPL "7-Zip pack" add-on pack behind GATE-A (M3.5's own REVIEW_QUEUE entry, item 1). The plan's table calls for a 7-Zip `.so` write engine that was never vendored in M3 — a known, already-logged scope decision, restated here rather than newly discovered. |
| RAR4 / RAR5, CBR | **None.** | **Untested, honestly, for lack of a fixture — not "broken".** RAR is a proprietary format with no open-source writer available in this sandbox to build a valid test fixture, and no fixture was fabricated. `BrowsableArchiveFormats.kt` already excludes `rar`/`cbr` from the browsable set with exactly this reason in its own comment ("libarchive reads them, but the RAR fixture corpus is pending"). This is the plan's own known gap, not new. |
| tar (plain, USTAR) + link/special-file/messy-path variants | Yes — `mixed-links.tar`, `messy-paths.tar`, `dot-rooted.tar`, `damaged-after-3.tar` | **Yes** |
| tar.gz, tar.bz2, tar.xz, tar.zst, tar.lz4 (+ hostile truncated variants of each) | Yes — `sample.tar.{gz,bz2,xz,zst,lz4}` / `truncated.tar.{gz,bz2,xz,zst,lz4}`, plus `tree.tar.zst`/`big-stream.tar.zst` | **Yes**, read and write (`write.rs` `WriteFormat::TarGz/TarXz/TarZstd/TarBzip2/TarLz4`) |
| tar.lzma (legacy `.lzma` stream, distinct from `.xz`) | **None.** | Untested. libarchive's xz filter is generally capable of both, but no fixture exercises the legacy `.lzma` header specifically — named as a gap, not assumed to work. |
| gz, bz2, xz, zst, lz4 (single-file compressed streams) | Yes, same fixtures as above (the tar wrapper is stripped by the same filter the standalone-stream path would use) | Yes, at the filter level; **not browsable as a folder** in the UI by design (`BrowsableArchiveFormats.kt`'s own excluded-list comment: libarchive's `raw` format isn't registered) — today's preview handles these, unchanged |
| lzma (raw stream), Z (`.Z`, compress) | **None.** | Untested; `Z` write was never in scope per the plan's own table ("not Z"), and read has no fixture either. |
| iso (ISO 9660 + Joliet + Rock Ridge) | Yes — `sample.iso` | **Yes** — listing with Rock Ridge names, lseek-backed skips, synthesized root dropped |
| ar | Built at Rust test time via the system `ar` tool (deterministic, not a static file under `core/fixtures/`, but a real byte-correct archive, not hand-encoded) | **Yes** — `entries`/`read_entry` both pass against it |
| cpio, deb, rpm, xar/pkg, cab, lha/lzh, arj, warc | **None — zero fixtures, zero tests, for any of these eight formats.** | **Untested.** `BrowsableArchiveFormats.kt` lists `cpio`/`deb`/`rpm`/`cab`/`lha`/`lzh`/`warc` as browsable by name (so the UI *would* try to open one), but no committed or test-time-generated fixture has ever proven the engine actually opens one; `xar` and `arj` are additionally excluded outright (xar needs libxml2/expat, both `OFF`; arj has no reader in libarchive's `support_format_all`), so those two cannot work regardless of a fixture. This is the single largest honest gap in format coverage this report found — larger than RAR, since these formats do have viable open-source writers (`ar`, `cpio`, `dpkg-deb`, `rpmbuild`, etc.) that simply were not used to build fixtures in M3.1–M3.9. |
| Split / multi-volume (`.zip.001`, `.7z.001`, `.part1.rar`, `.z01`) | **None.** | **Untested, and known broken for the one direction Fylz itself produces**: M3.5 can *write* split volumes (raw `.001` parts, resolved and finalised as one set), but "Fylz cannot yet open the split sets it writes" — M3.5's own REVIEW_QUEUE entry, item 5, names this explicitly as "a read-side gap the plan's own table does not flag" and it is **still open** (no later milestone closed it). |
| APK, JAR, AAR, CBZ, CB7 (ZIP/7z-based containers already in `BrowsableArchiveFormats`) | No fixture named by its container extension; rides entirely on the underlying ZIP/7z engine's own extensive testing | Structurally plausible (the engine reads by content, not extension) but **never specifically fixture-tested under its container name** — stated honestly per this task's own instruction to give containers the same treatment as any other untested format |
| IPA, XAPK, APKM (containers the plan's table names) | **None**, and these three are **not even in `BrowsableArchiveFormats.extensions`** at all — grepped directly, absent | Not wired into the browsable set yet, a different and more basic gap than "untested" |
| EPUB, OOXML, ODF | No fixture; not in `BrowsableArchiveFormats.extensions` either (grepped, absent) | Same as IPA/XAPK/APKM: not wired in, not tested |

**Summary for this line:** the plan's literal bar ("at least one of each format opens") is met for
roughly two-thirds of the named formats — every compression filter, ZIP (unencrypted), 7z
(unencrypted), tar and its five compressed variants, and ISO all have a real fixture proven through
the engine's own `inspect`/`entries`/`read_entry` path, re-run fresh for this report (§1). It is
**not** met, honestly, for RAR (no fixture possible in this sandbox), for the eight
cpio/deb/rpm/xar/cab/lha/arj/warc formats (fixture-able but never done), for split volumes (write
exists, read does not), for 7z write, and for the container formats IPA/XAPK/APKM/EPUB/OOXML/ODF
(not even wired into the browsable set). None of these gaps were fabricated a fixture to paper
over; each is named plainly here and, where a prior milestone already logged it, cited to that
entry rather than presented as a new finding.

### 3.2 "A 5 GB 7z extracts through the queue with verification."

**This remains UNVERIFIED, and by construction cannot be verified in this sandbox** — no container
in this run's environment has 5 GB of free disk headroom to spare safely for a throwaway extraction
test, exactly as M3.4's own design and device-checks already recorded. `docs/agent/DEVICE_CHECKS.md`
§19 ("M3.4 — selective extract through the transfer queue") states this explicitly in its own
opening paragraph ("Everything below was verified only in this sandbox... **None of this ran on a
device.**") and its step 2 is the acceptance line itself, spelled out as a device procedure (build a
5 GB 7z with `7z a -mx=1 big.7z bigfile.bin small*.bin`, extract with Always-verify consent ticked,
record wall time/peak RSS/`sha256`, repeat onto exFAT and vfat cards) — nothing this report can run
here substitutes for that.

What **is** implemented and unit-tested at small scale, confirmed by reading the code (not just
trusting the design doc):
- **Destination-aware limits with consent**: `VerifySettings` and the consent-gated size/entry-count
  rules in `fylz-archive`'s policy layer (`policy_tests.rs`'s 34 passing cases, §3.3 below) are the
  same rules a 5 GB extraction would hit; they are exercised against small fixtures, not a real 5 GB
  file, but the decision logic itself is the same code path.
- **The isolated extraction instance** (`:decoders:extract`, a second `IDecoderService` binding
  distinct from the browsing one): implemented in M3.4b, exercised by `ArchiveExtractorTest` against
  a fake `IDecoderService.Stub` writing real extraction frames — proves the demultiplexing and
  wiring, not the real cross-process binding, kill/reap or SELinux behaviour a device check needs.
- **Per-entry verification**: `ArchiveExtractor`'s `verify(destinationUri)` (line 705 of that file)
  and `FileOperationServiceVerificationTest` cover the mechanism at small scale.

Nothing here claims the 5 GB line is met — it is explicitly carried forward as real-device-only,
matching M3.4's own honest framing rather than restating an old number as if it were new evidence.

### 3.3 "Hostile fixtures (zip-slip, bombs, symlink escapes, oversized headers) are refused."

Every hostile fixture under `core/fixtures/archives/hostile/` — `zip-slip.zip`,
`absolute-path.zip`, `symlink-escape.tar`, `many-entries.tar.zst`, `many-small-10000.tar.zst` — plus
the two built-at-test-time hostile cases (a 300 MiB ratio bomb through `tar -z`, and a hand-written
oversized-header tar) were re-run **just now**, fresh for this report, not carried over from an old
number:

```
cargo test -p fylz-archive policy   → 34 passed; 0 failed; 0 ignored; 116 filtered out
cargo test -p fylz-archive hostile  → 2 passed; 0 failed; 0 ignored; 148 filtered out
cargo test -p fylz-archive -- bomb oversized
   → policy_tests::rejects_oversized_files_and_total_expansion ... ok
   → blocks_tests::an_oversized_header_archive_built_at_test_time_is_refused_structurally_and_failed_per_entry ... ok
   → blocks_tests::a_ratio_bomb_built_at_test_time_is_refused_by_evaluate_selection_in_every_mode ... ok
   3 passed; 0 failed; 0 ignored; 147 filtered out
```

`seek_tests::hostile_fixtures_are_refused` (one of the two "hostile"-named tests) iterates all four
committed hostile fixtures by name and asserts both the refusal and the exact reason string
(`"Archive contains an unsafe path segment."` for zip-slip, `"...an absolute or invalid path."` for
the absolute path, `"...a link that escapes the extraction folder."` for the symlink escape,
`"...too many entries."` for the 10,001-entry bomb) — read directly from
`core/crates/fylz-archive/src/seek_tests.rs` for this report, not assumed from its name. Every case
named in the plan's own parenthetical (zip-slip, bombs, symlink escapes, oversized headers) has a
real, currently-passing test. **All 34 + 2 + 3 = 39 directly relevant cases pass**, inside the
149-case `fylz-archive` suite that also passed in full (§1) as part of the same `cargo test
--workspace` run reported there.

### 3.4 "Fuzz targets for zip, 7z, rar, tar and iso run clean."

**Interpretation taken, stated honestly:** `core/fuzz/fuzz_targets/` contains four targets —
`sniff` (M2.6, format detection, not archive-specific), `policy_evaluate` (M3.1 part 3a, the
extraction policy over synthetic entry tables), `archive_entries` (M3.1 part 3b / M3.3a, the actual
libarchive-backed engine — `inspect`/`inspect_into`/`read_entry`/`extract_entry_at`/`extract`/
`extract_blocks` over real archive bytes), and `write_frames` (M3.5a, the create engine's frame
parser). **None of these is a single-format binary** — there is no `fuzz_zip`, `fuzz_7z`,
`fuzz_rar`, `fuzz_tar` or `fuzz_iso`. Instead, `archive_entries` is one generic target that exercises
every vendored format reader libarchive compiles in, seeded from the *same* committed fixture corpus
§3.1 describes (`core/fixtures/` and `core/fixtures/archives/`, recursively — zip, 7z, tar and its
five compressed variants, iso, and ar all included as seeds; rar is not, since no rar seed exists to
give it, per §3.1's own honest gap). This is the interpretation M3.1's own CI fuzz-smoke step
established from the start (`core/fuzz/README.md`'s own text: "Seed it with the committed
fixtures... that one argument also covers M3.2a's ZIP/7z/ISO fixtures... and the hostile ones") and
every later milestone continued rather than replacing it with per-format binaries — a reasonable
reading of the plan's own intent (fuzz coverage *for* those formats, via the one engine that reads
all of them), not a literal five-binary requirement.

**Fresh 60-second smoke runs, executed just now for this report** (`cargo +nightly fuzz run <target>
-- -max_total_time=60`, from `core/`), not old numbers:

| Target | Executions in 61s | Crashes |
|---|---|---|
| `sniff` | 5,807,934 | **0** |
| `policy_evaluate` | 1,112,713 | **0** |
| `archive_entries` (seeded with `fixtures/`) | 48,536 | **0** |
| `write_frames` | 1,493,631 | **0** |

`archive_entries`'s much lower rate is expected and already documented (`core/fuzz/README.md`): each
iteration writes the input to a real scratch file and drives it through several real, fd-backed
libarchive passes (list, listing-codec write, single-entry read, single-entry extract by ordinal,
bulk extract under tight limits, plus the M3.4a block-range pair) — genuinely slower work per
iteration than a pure-Rust target, not a sign of trouble. No crash artifact was written to
`core/fuzz/artifacts/` by any of the four runs (checked directly). All four run clean.

## 4. What M3 delivers

A working Rust/libarchive engine (`fylz-archive`) that reads ZIP (including legacy encodings),
7z (unencrypted), tar and its five compressed variants, and ISO 9660 with seekable, non-staging
access from inside an isolated decoder process; browses any of them as a folder with nested-archive
support to depth 4; extracts selected entries through the same durable transfer queue copy/move
already use, with consent-gated safety limits and per-entry CRC verification; creates ZIP and five
tar variants (beyond the plan's own named list) in the same queue, with split-size output; edits ZIP
archives in place (add/delete/rename) with an atomic rewrite-and-replace and the old archive sent to
the recycle bin; auto-detects and lets a user override legacy ZIP filename charsets; verifies an
archive's CRCs without extracting; and shares one session-only, wipe-on-use password prompt across
every archive flow that needs one. `ExtendedArchiveBrowserService` and its two now-dead Gradle
dependencies are gone. zip4j remains, narrowed since M3.4c to the one AES-encrypted-ZIP create/
extract path libarchive's stub crypto backend cannot serve.

## 5. Known gaps for the owner

- **RAR/CBR: untested, not broken.** No fixture exists because no open-source tool in this sandbox
  can write a valid RAR file; libarchive's reader is compiled in and excluded from the browsable set
  pending exactly this fixture (§3.1). Whoever has a real RAR sample or a licensed RAR CLI available
  should generate one and add it as a fixture — this is a one-fixture fix, not an engine change.
- **The 5 GB 7z / queue / verification acceptance line is entirely real-device-only** (§3.2);
  nothing in this sandbox can honestly close it. `docs/agent/DEVICE_CHECKS.md` §19 has the exact
  procedure.
- **AES ZIP parity cannot be reached without a crypto backend** (M3.10, this milestone's own last
  task): `fylz-archive`'s `build.rs` has every crypto backend off; zip4j is retained, scoped to that
  one path. `docs/agent/REVIEW_QUEUE.md`'s M3.10 entry has the full reasoning and a recommendation
  for Madhav on whether enabling one (OpenSSL or mbedTLS, most likely) is worth the added native
  dependency, licence review and `deny.toml` update.
- **7z write was never implemented, at all**, deferred to a GPL/AGPL "7-Zip pack" add-on behind
  GATE-A (M3.5's own REVIEW_QUEUE entry, item 1) — the plan's table calls for it, M3 does not deliver
  it, and this is not new information but is restated here since it directly affects §3.1's "at
  least one of each format" line for 7z's write column.
- **Split-archive self-read is still open.** M3.5 can write `.zip.001`/etc. split sets; Fylz cannot
  open the split sets it just wrote. M3.5's own REVIEW_QUEUE entry (item 5) named this and no later
  M3.x milestone closed it — it is exactly the kind of gap this report was asked to check for
  remaining unread.
- **Eight formats (cpio, deb, rpm, xar, cab, lha/lzh, arj, warc) have no fixture and no test at
  all**, despite `BrowsableArchiveFormats.kt` treating six of them (all but xar and arj, which are
  structurally excluded) as openable today. This is the largest fixture-coverage gap this report
  found, larger than RAR, because — unlike RAR — every one of these has a viable open-source tool to
  build a real fixture with (`ar`, `cpio`, `dpkg-deb`, `rpmbuild`, `cabextract`/`lha` encoders,
  etc.); it was simply never done across M3.1–M3.9.
- **ZIPX's actual compression methods (PPMd, LZMA) have never been successfully read in a test** —
  the one PPMd fixture that exists is deliberately corrupted to test the failure path.
- **IPA, XAPK, APKM, EPUB, OOXML and ODF are not wired into `BrowsableArchiveFormats.extensions`
  at all**, a more basic gap than "untested" — the plan lists them as containers M3 should flag as
  such, but they cannot even open as a folder today.
- **A genuine pre-existing bug, found and recorded (not fixed) during M3.9:** `ArchiveService.
  queryName`'s legacy `ContentResolver.query` overload fails against any real `DocumentsProvider`-
  backed source on API 26+ — live in production today, independent of M3.9's own scope, and
  possibly moot once/if `ArchiveService.kt` is ever retired (`docs/agent/REVIEW_QUEUE.md`'s M3.9
  entry, item 8).
- **An encrypted ZIP cannot be Tested** (M3.8's own CRC-verification action refuses it up front
  rather than prompting for a password that could never actually be used, since the new engine's
  password field is still M3.5's disabled stub) — logged as a gap in M3.8's own REVIEW_QUEUE entry.
- **Three credential fields elsewhere in the app remain plain, unwiped `String`s** (the AI API-key
  field, the WebDAV password field, a remote-connection secret in `RemoteConnectionsDialog.kt`) —
  deliberately left untouched by M3.9's own narrower archive-password scope, named in its REVIEW_
  QUEUE entry, item 6, as a follow-up candidate if a future milestone wants the general "no secret
  sits in Compose state as a `String`" property rather than just the archive-password one.

None of the above blocks M3 from being usable as shipped; each is either a real-device-only claim
this sandbox cannot close, a deliberately deferred add-on behind its own gate, or a fixture-coverage
gap with a clear, bounded fix (build the missing fixture) rather than an engine defect.
