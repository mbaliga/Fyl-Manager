# Review queue

Per `docs/agent/MASTER_PLAN_ADDENDUM_1.md` §A.1: a **review gate** (GATE-M1, GATE-M2, GATE-M4,
GATE-M8's technical parts) no longer stops the run. At each one this run writes the milestone
report, adds the relevant entries to `docs/agent/DEVICE_CHECKS.md`, appends an entry here, and
continues. Madhav reviews and device-tests everything in one pass at the end of the Android work
(§A, opening paragraph) rather than gate by gate; this file is the running list of what that pass
needs to cover, so nothing found along the way gets lost before then. Each entry: gate ID, what
needs reviewing or testing, the relevant commits, and the risk if it turns out to be wrong. **Hard
gates** (GATE-A, GATE-M8's model choice, GATE-M10, GATE-D-cloud, GATE-UT, D5, GATE-C1, GATE-C2) are
not logged here as "passed" — they are logged only once this run reaches the point of no return
before one and stops short of publishing, submitting or enabling anything; none has been reached
yet.

---

## GATE-M1 — Phase 1 (M1) device checks

**Milestone:** M1, tasks P1.8–P1.14 (`docs/agent/MASTER_PLAN.md` §5, "M1. Finish the engine").
Full per-task detail: `docs/agent/PROGRESS.md`'s task table and `docs/agent/REPORT-M1.md`.

**What needs reviewing or testing:**
- Every device check `docs/agent/DEVICE_CHECKS.md` lists as owed for P1.8–P1.14 specifically:
  §1a (rotate/background mid-task with "Don't keep activities", P1.10), §5 (SFTP against a real
  server — carried from Phase 0, still open), §6–§7 ("Open with Fylz" and launcher shortcuts —
  also carried from Phase 0), §10 (100,000-entry folder listing budget, external-change
  notifications, off-main-thread I/O — P1.11), §11 (FTS5-vs-FTS4 probe, MediaStore-generation
  rescan-skip, indexed-vs-live-walk search parity — P1.12), §12 (PDF rotation and searchable-OCR
  round trip on a real render — P1.13).
- Code review of the in-app clipboard/destination-chooser flow (P1.8) and the root-hardening
  change (P1.9), since both touch how other apps can reach Fylz's own `DocumentsProvider` and
  neither has a device check of its own beyond what's listed above.
- Confirmation that nothing in P1.9's `FLAG_SUPPORTS_IS_CHILD` change regresses a real third-party
  picker's ability to browse Fylz's exposed roots (single-document access is unaffected by design,
  but only a real cross-app picker request proves it).

**Relevant commits:**
- `8aa1496` — P1.8: clipboard, in-app destination chooser, nested-destination fix
- `a8cf712` — P1.9: omit `FLAG_SUPPORTS_IS_CHILD` for other apps
- `dbf4f71` — P1.10: `BrowserViewModel`, real saved state, process-death survival
- `aef894f` — P1.11: paged listing, provider change notifications, off-main-thread I/O
- `329bcfd` — P1.12: one SQLite-backed index, replacing four JSON files and nine indexers
- `d951f1c` — P1.13: remove dead duplicates, consolidate `PdfToolService` into `PdfPageTools`
- `f15a314` — P1.14: update README, ARCHITECTURE and CHANGELOG to match Phase 1 reality

**Risk if it turns out wrong:**
- P1.10/P1.11: a real device could behave differently from Robolectric's hosted `DocumentsProvider`
  under real process death or a real 100,000-file SD card/USB listing — the failure mode is a lost
  session (tabs/selection/sort silently reset) or a frozen/janky large-folder browse, not data
  loss, but it is the app's core everyday-use path, so a regression here is highly visible.
- P1.9: if a real third-party picker turns out to depend on `FLAG_SUPPORTS_IS_CHILD` being present
  for a use this session didn't anticipate, some other app's "open with"/"save to" flow against
  Fylz's roots could break; low likelihood (single-document access is unaffected by design) but a
  compatibility regression for other apps, not just Fylz's own UI.
- P1.12: FTS5-vs-FTS4 availability differs by device/SQLite build; if a real device's search
  results diverge from what the indexed-vs-live-walk parity tests assume, the visible symptom is
  wrong or missing search results, which for a file manager is a trust-relevant defect.
- P1.13: `pageDrawMatrix`'s correctness for every rotation was proven only against matrix math in
  a unit test, never a real rendered bitmap; if a real rotation is wrong on-device, extracted PDF
  pages would show sideways/upside-down/mirrored content, or OCR text at the wrong on-screen
  position — a visible correctness defect in a feature already live in the UI.

---

## GATE-M2 — `fylz-core` bootstrap: APK size and cold-start review

**Milestone:** M2, tasks M2.1–M2.6 (`docs/agent/MASTER_PLAN.md` §5, "M2. fylz-core bootstrap").
Full per-task detail: `docs/agent/PROGRESS.md`'s task table and `docs/agent/REPORT-M2.md`.

**What needs reviewing or testing:**
- The APK size delta per ABI, now measured and reported in full in `docs/agent/REPORT-M2.md` §5:
  +2,213,079 B (+4.14%) total, 568,464/394,108/546,704 B per ABI (arm64-v8a/armeabi-v7a/x86_64) for
  the new `fylz-ffi-android` cdylib plus JNA's `libjnidispatch.so`, well inside the master plan's
  own +12 MB-per-ABI core budget (section 4.2) — Madhav's own approval that this is acceptable
  before more native code lands in M3 onward. **New finding to review alongside it:** JNA also
  ships `libjnidispatch.so` for `armeabi`/`mips`/`mips64`/`x86`, ABIs the app builds no core for and
  no target device needs — 524,052 B of dead weight in the universal APK, recorded but not fixed
  (candidate follow-up: `packaging { jniLibs { excludes += ... } }` or ABI splits; see
  `docs/agent/REPORT-M2.md` §5 and §7).
- The cold-start delta, logged as **device-needed** (`docs/agent/DEVICE_CHECKS.md`'s new M2 entry)
  — confirm on a real device that the architectural expectation (the native library loads only
  inside the isolated `:decoders` process, which nothing calls yet from the main app's own startup
  path — see `REPORT-M2.md` §5 for exactly what was read to support this) actually holds, since
  this run has no device to measure it on.
- The M2.3 16 KB page-size finding: an NDK r28+ `dev-android` profile build linked at the classic
  4 KB page size where `--release` linked at 16 KB with no other variable changed, fixed with an
  explicit linker flag (`core/.cargo/config.toml`) rather than relying on the toolchain's default.
  Worth Madhav's own sign-off that pinning it this way is the right long-term fix, not a workaround
  to revisit.
- **Correction to M2.4's timeout contract (`c9b184a`, 2026-09-25, found by the M3.2 design review):** the per-call timeout in `DecoderClient` did not abandon a hung Binder call -- `withTimeoutOrNull { withContext(IO) { … } }` waits for the blocking body, so the unbind that lets the platform reap `:decoders` ran only after the native call returned. Section 4.4's "kill-and-restart on a timeout" was therefore not true between `6de3490` and `c9b184a`; nothing shipped called the client in that window, so no user-visible behaviour existed to regress. Fixed by running each transaction in a client-owned job outside the caller's scope, with elapsed-time tests. Two smaller pre-existing points the fix left alone, for the review pass: `DecoderClient.pending` is a non-volatile field shared between the main-thread `ServiceConnection` callbacks and the calling coroutine, and a `dropConnection()` racing an in-flight `ensureConnected()` can surface as a `CancellationException` rather than the documented `null`. Both are in scope for M3.2b, which makes the first real calls. **Closed by M3.2b** (`DecoderClient.kt`, the commit after `8e35497`): `pending` is now an `AtomicReference` claimed with compare-and-set, shared safely between the main-thread `ServiceConnection` callbacks and the calling coroutine; a `dropConnection()` racing an in-flight `ensureConnected()` (a binding that dies while a call is still waiting to connect) returns the documented failure value (`DecoderCall.Failed`, `null`/`false` for `sniff`/`ping`) and never a `CancellationException`, while the caller's *own* cancellation still propagates (`ensureActive()` tells the two apart); `dropConnection()` is a no-op when nothing is pending, so a callback and a caller noticing the same death do not double-unbind. Pinned by `DecoderClientTest`'s dropped-bind, caller-cancelled and bind-returns-false cases. What still needs a device: the same sequence against a real `bindService`/`onBindingDied` (`DEVICE_CHECKS.md` section 17, items 5 and 6).
- The licence findings in `docs/agent/REPORT-M2.md` §6 (uniffi MPL-2.0, JNA's Apache-2.0 choice
  under its Apache-2.0/LGPL-2.1 dual licence, libarchive's BSD-plus-exceptions `COPYING`, and the
  ~90-crate transitive dependency tree uniffi's bindgen CLI pulls in) — a second pair of eyes on
  `THIRD_PARTY_NOTICES.md`'s new entries before any of this ships publicly.

**Relevant commits:**
- `6ae3650` — M2.1: bootstrap the `fylz-core` Rust workspace
- `dad5d6d` — M2.2: `fylz-ffi-android` uniffi skeleton, generated into the Kotlin core package
- `851b2f8` — M2.3: wire `fylz-core` into the Gradle build via `buildCore` + 16 KB alignment check
- `0fc9473` — M2.3 fixup: install cargo-ndk and the Android NDK in CI
- `6de3490` — M2.4: `DecoderService`, the isolated decoder process
- `9226754` — M2.5: `fylz-sniff` content sniffing, wired into `fylz-ffi-android`
- `27637e8` — M2.6: CI additions (`cargo test`/`clippy`/`deny`, fuzz smoke run)

**Risk if it turns out wrong:**
- APK size: if the per-ABI delta turns out larger than expected once measured, that compounds with
  every later milestone's own native additions (M3's libarchive, M4's disk-image code, and so on)
  — worth catching the trend early rather than after several more crates have shipped.
- Cold-start: if the "loaded lazily, isolated-process-only" architectural expectation turns out
  wrong on a real device (for instance if Android eagerly probes an isolated service's manifest
  entry in a way that touches the `.so` earlier than expected), every user sees it on every app
  launch — a small per-launch regression is the most broadly-felt kind of regression there is.
- 16 KB alignment: if the explicit linker flag doesn't actually cover a future crate/profile
  combination the same way, the failure mode is the app refusing to install at all
  (`dlopen failed`) on a real 16 KB-page-size device — a hard install failure, not a soft
  degradation, and Android is moving toward 16 KB as a platform requirement.
- Licences: MPL-2.0/LGPL/dual-licence choices recorded wrong would be a real compliance exposure
  once this ships publicly, per the master plan's own licence policy (§2.2) — this is exactly the
  class of finding the plan says to treat as a gate if a licence turns out to break policy, so it
  stays in this queue for a second read rather than being treated as fully closed by this run's own
  verification alone.

---

## MC.0 — action registry (not a gate; items for the review pass)

**Milestone:** MC.0 (`docs/agent/MASTER_PLAN_ADDENDUM_1.md` §C1/§D; full design in
`docs/agent/DESIGN-MC0-ACTION-REGISTRY.md`). Not a gate — MC.0 is fully green on this run's own
gate (`docs/agent/PROGRESS.md`'s MC.0 row) and device checks are logged in
`docs/agent/DEVICE_CHECKS.md` §16 — but a refactor that touched most of the UI's menus, shortcuts
and gestures in one pass is exactly the kind of change Madhav's review pass should look at closely,
so these product/design questions and cross-references are logged here rather than only in the
design doc.

**What needs reviewing:**
- `fylz.recycle` still has no confirmation dialog — a product question, not a bug (preserved
  behaviour, design §6.2/item 2): should Recycle get a typed-phrase or a simple Yes/No confirmation
  before MC.1 lets a third party register a `confirm: Always` action of its own, or is "recycle is
  reversible, only permanent delete confirms" the intended product shape going forward?
- The master plan's own internal conflict, not something MC.0 introduced: §M12.1 says F5/F6 copy
  and move between dual-pane panes; §M12.3's keyboard map separately says "F5 refresh". MC.0 bound
  F5 to `fylz.refresh` (alongside Ctrl+R) since no dual pane exists yet to need F5 for anything
  else (design §2.5's shortcut-table paragraph); `registry.problems`'s `ShortcutConflict` detector
  is what will flag this the moment M12.1 tries to claim F5 for pane-copy, but the master plan
  itself should be corrected to say which one wins rather than relying on the conflict detector to
  surface it at implementation time.
- The placement-model extensions to `MASTER_PLAN_ADDENDUM_1.md` §C1, each recorded as a deviation
  in `docs/agent/DESIGN-MC0-ACTION-REGISTRY.md` §2.2/§8 and in this MC.0 row's own Notes: `Room`
  (the addendum's text already implies room items are registry lookups, but never named the
  placement type), `Menu` (three distinct menus — overflow, sort, archive-tools — rather than one
  generic "toolbar" placement), `Toolbar.bar` (two separate bars, top app bar and browser row),
  `Gesture.targetWhen` (double-tap needs to split into two different actions depending on what was
  tapped), and `requiresTarget`/`ActionTarget` (several actions act on a specific row/tab that is
  not part of the `BrowserState` snapshot). Worth folding into the addendum itself so MC.1+ isn't
  reading a placement model the addendum's own text doesn't describe.
- Favourite (`fylz.favourite.toggle`), Open root (`fylz.open-root`) and Recycle Bin
  (`fylz.recycle-bin`) have no placement on a narrow screen at all today — they live only in the
  Library rail, which draws only when `wide` (§2.6). This is preserved, pre-existing behaviour
  (design §1's own inventory), not something MC.0 changed, but it is a real phone-user gap MC.3
  should close rather than carry forward indefinitely.
- The JNA dead-ABI finding already logged under GATE-M2 above stays there — MC.0 didn't touch it,
  noted here only so the review pass doesn't look for it under this entry by mistake.

**Relevant commits:** `ed9e61b`, `432c552`, `71e6b84`, `d3c3e9d`, `959e696`, and this commit
(MC.0f).

**Risk if it turns out wrong:** the main risk is a shortcut or gesture firing on the wrong target,
or not firing at all, on a real device — the golden test (`ActionResolverGoldenTest`) and the
keyboard/gesture unit tests (`KeyRouterTest`, `GestureDispatchTest`, `ShortcutTableTest`) all run
against Robolectric/plain-JVM fixtures, never a real hardware keyboard, a real shake sensor, or a
real edge-drag through `SpatialShell`; `DEVICE_CHECKS.md` §16 is what still needs a real device.
Secondary risk: the recycle-without-confirmation gap, if left unresolved, means a bulk recycle from
the selection bar or a shortcut (Delete) has the same one-keystroke blast radius it always had —
not a regression, but not improved by this refactor either.

---

## M3.1 part 3 — extraction policy in Rust: three behaviour deviations from the Kotlin policy

**Milestone:** M3.1 part 3 (`docs/agent/DESIGN-M31-PART3-EXTRACT-AND-POLICY.md`, decision 1 and
section 6; full per-commit detail in `docs/agent/PROGRESS.md`'s `M3.1 (part 3)` row). Not a gate:
the Rust policy is green against every ported Kotlin test case, but three of its rules are
deliberately **not** what `ArchiveExtractionPolicy.kt` does today, and they decide which archives
the app will accept once the Rust engine is the one inspecting (M3.2) and extracting (M3.4). The
Kotlin copy stays, with a header line pointing at `policy.rs`, until M3.4 moves `ArchiveService`'s
read path onto the engine (design decision 2, rescheduled from M3.2/M3.3 by the M3.2 design's scope
note); until then nothing user-visible changes.

**What was decided, and needs a second read:**
- **Ratio rule adaptation (decision 1).** zip4j reported a per-entry compressed size for every
  ZIP member; libarchive reports none for any format, so `EntryMetadata::compressed` is
  `Option<u64>` and **always `None` from the engine**. The two per-entry rules that need it
  ("implausibly compressed entry", "suspicious compression ratio") run only when it is `Some`, and
  an **archive-level** ratio (sum of declared uncompressed sizes over the archive's own byte
  length, against the same `max_compression_ratio`, same reason string; a zero-length archive
  declaring bytes counts as infinite) runs for every format in their place. Consequences: for ZIP
  the Rust rule set is a strict subset of Kotlin's (a single member compressed 500:1 inside an
  archive whose overall ratio is under 200:1 now passes preflight -- `extract()`'s runtime byte
  caps in 3b are the defence against a header that lies); for tar/gz/xz/zst/lz4 streams, which the
  app never opened before, the archive-level rule is the only ratio rule. Two Kotlin test cases
  (`negative-compressed` in the fuzz test, `unknown.bin` with compressed -1 in the unit test)
  flip from refused to allowed in the port; `policy_tests.rs` says so at each one, next to the
  twin case the archive-level rule does refuse. An unknown *uncompressed* size is still refused.
- **The link rule (section 6.2), a rule Kotlin never had.** A `Symlink` whose target is absolute
  (leading `/` or `\`, or a drive letter) or whose `..` segments climb above the extraction root
  when joined onto the link's own directory, a `Hardlink` whose target fails the path rules, or
  either with no target at all, refuses the whole archive with a new reason string ("Archive
  contains a link that escapes the extraction folder."). In-tree relative links are allowed and
  (3b) `extract()` never materialises any link -- SAF cannot create one -- counting them in
  `ExtractReport.skipped_links` instead. The question for review is the *refuse-the-archive*
  choice: a tarball with one stray `/etc/passwd` symlink becomes unextractable rather than
  extracted-minus-the-link. The design chose refusal to meet M3's acceptance criterion "symlink
  escapes are refused" literally; a per-entry skip-with-warning would be the alternative. Related
  3b choice the design does not make: `Other`-kind entries (device nodes, fifos, sockets) are
  never offered to the `DestinationProvider` either -- `ArchiveEntry` carries no kind, so the
  provider could not decline them itself, and SAF cannot create them -- and are counted in
  `ExtractReport.skipped`; the alternative (offer them as empty regular files, as some unzip
  tools do) was not taken.
- **Lossy names (section 6.1; 3b's `inspect` and, beyond the design's text, `extract`).** A
  non-UTF-8 entry name (legacy CP437/GBK ZIPs) is decoded with `String::from_utf8_lossy` and
  flagged `name_lossy = true` instead of failing the whole inspection with `NonUtf8Path`; the
  policy validates the lossy string (its structural rules read the same through replacement
  characters). `extract` names entries the same way, so a `Selection::Paths` taken from an
  `Inspection` selects its own entry -- the design says this only for `inspect`, and 3b applied it
  to `extract` because the two would otherwise disagree on the one name the caller has.
  `entries()`/`read_entry(fd, path)` keep `NonUtf8Path`, since an exact-match lookup on a lossy
  name is ambiguous; M3.7 gives both a real charset. Worth confirming that a lossy name reaching the UI in M3.2 is acceptable as
  an interim display, and that two distinct raw names collapsing to the same lossy string (both
  then refused as duplicates by the policy) is the intended conservative outcome.
- Smaller ported-semantics choices, made for parity rather than taste: name lengths are measured in
  UTF-16 code units (Kotlin's `String.length`), not bytes -- a filesystem's `NAME_MAX` is in
  bytes, so a 255-character non-ASCII name that passes here could still fail at SAF; and
  "blank" uses Java's whitespace set, not Unicode `White_Space` (they differ only at U+0085 and
  U+001C..U+001F).

**Relevant commits:** `1f0a9a6` -- M3.1 part 3a (policy, parity tests, `policy_evaluate` fuzz
target); (this commit) -- part 3b (`extract()`, `inspect()`, the seekable open path,
`archive_entries` fuzz target).

**Risk if it turns out wrong:**
- Ratio adaptation: too lax, and a ZIP whose one bomb member sits inside an otherwise ordinary
  archive passes preflight -- bounded, not unbounded, because `extract()` stops at
  `max_file_bytes`/`max_total_uncompressed_bytes` while writing, so the exposure is wasted I/O up
  to those caps, not disk exhaustion. Too strict (the archive-level rule), and a legitimately
  highly compressible archive (logs, sparse database dumps, a tar of empty files) is refused with a
  message that blames compression -- a usability defect the old ZIP-only policy did not have for
  streams, and a real one for `.tar.xz` source tarballs whose overall ratio can exceed 200.
- Link rule: too strict, and common Unix tarballs (anything with a `latest -> v1.2` or
  `/usr/share`-style symlink) are refused outright; too lax (if the depth arithmetic is wrong for
  some shape), and M3's "symlink escapes are refused" criterion is not met -- though with
  `extract()` never writing a link, the escape could only ever be a policy-reporting error, never a
  file written outside the destination.
- Lossy names: a wrong choice here shows up as garbled entry names in the Inspect dialog and
  preview for legacy ZIPs from M3.2 until M3.7, or as a spurious "duplicate paths" refusal on an
  archive whose names differ only in bytes the replacement character erases.

---

## M3.2 — a seekable descriptor into the decoder process, no whole-archive staging

**Milestone:** M3.2 (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` rev 2; per-commit detail in
`docs/agent/PROGRESS.md`'s `M3.2` row; device checks in `docs/agent/DEVICE_CHECKS.md` section
17). Not a gate: log-and-continue. Commits `8e35497` (a, fixtures and seek-discriminating Rust
tests), `1983c0c` (b, `archive_inspect` over uniffi, `DecoderService.inspectArchive`,
`DecoderClient.inspectArchive`/`DecoderCall`, the two GATE-M2 points closed) and the M3.2c
commit (`ArchiveSource`, `ArchiveInspector`, the three call sites off zip4j).

**What was decided, and needs a second read** (the design's section 3 list, then what
implementation added):

1. **The engine refuses non-seekable input outright** (section 2.1) instead of streaming what it
   can. The pipe-fed negative controls in `seek_tests.rs` are the evidence for what streaming
   would lose: a streamed ZIP lists with no sizes at all (the policy then refuses it as "unknown
   size"), a 7z's data always fails with "Seek error" and an encoded-header 7z cannot even list,
   while an in-order ISO -- honestly recorded -- lists and reads through a pipe too.
2. **The Kotlin policy copy's deletion moves to M3.4** (section 0). Until then Inspect (the Rust
   rules, via `:decoders`) and Extract (`ArchiveService.extractZip`'s Kotlin rules) can disagree
   on the same ZIP; the Inspect dialog shows the Rust verdict and the Extract button re-checks
   with the Kotlin one.
3. **`compressed` stays `None` for good**: libarchive has no per-entry compressed size, so once
   M3.4 deletes the Kotlin copy, ZIP loses the per-entry ratio rule part 3 decision 1 assumed it
   would keep; the archive-level ratio and the runtime caps are the defence.
4. **Non-UTF-8 names are decoded lossily and flagged** rather than failing (section 2.4), until
   M3.7; the UI shows replacement characters and a one-line note.
5. **The link rule and link skipping in `extract()`** (part 3 section 6): a rule the Kotlin policy
   never had, now reachable from the UI through every non-ZIP family Inspect accepts.
6. **No 256 MB address-space enforcement**; `max_listing_entries` (200,000, measured at
   +27,232 KiB peak RSS for exactly that many entries, about 139 B each) is the only memory bound
   in `:decoders`.
7. **Section 4.4's kill-and-restart on timeout did not work before `c9b184a`**; corrected under
   GATE-M2 as well, where M3.2b's closure of the two remaining points is recorded.
8. **"Remote streams stage to cache" is structural only**: the staging path exists and is tested
   with a pipe-backed provider, but no remote (SFTP/SMB/WebDAV) archive opens after M3.2 -- they
   have no `Uri` yet.
9. **Inspect is widened beyond the ZIP family** (7z, ISO, tar and the five compressed-tar MIME
   types in the picker); Extract is not (ZIP-only button, `fylz.extract`'s `enabledWhen` unchanged).
10. **Extraction still stages whole archives** until M3.4.
11. **Full-listing transport deferred to M3.3**; M3.2 carries the first 500 rows in the Parcelable
    (measured under 256 KB for 500 long-ish paths). Closed by M3.3: the listing streams through a
    pipe in the `FZL1` codec (M3.3 entry below).
12. **No idle-unbind policy** for the application-scoped `DecoderClient`: once used, `:decoders`
    lives as long as the app process; M3.3 measures and decides. Closed by M3.3: idle unbind after
    60 s with nothing in flight (M3.3 item 15).
13. **`inspect`/`extract` drop libarchive's synthesized root entry** (`is_archive_root`, M3.2a): an
    ISO image's root is listed as a directory named `.`, which the Kotlin-parity `.`-segment rule
    refused, so every ISO would have shown "unsafe path segment". Entries *under* such a root
    (`./file` from `tar -C dir -cf x.tar .`) are still refused by that rule -- a policy question
    for M3.3/M3.4: should `./`-prefixed paths be normalised before the rules run?
14. **`ArchiveEngineError::Internal`** exists beyond the design's four variants so the `From`
    conversion is total without calling an engine bug "corrupt"; it maps to `OUTCOME_INTERNAL`.
15. **`total_uncompressed` sums non-directory entries only**; an ISO directory's extent length is
    not bytes an extraction writes.
16. **A provider whose stream disagrees with its declared `COLUMN_SIZE` in either direction is a
    `SizeMismatch`** (the design names only over-sending). A stale size on a changed file therefore
    refuses staging rather than copying a file the provider misdescribed; worth confirming that is
    the wanted strictness.
17. **`DecoderClient` also turns a `RuntimeException` into `Failed`** (a `bindService` that throws
    `SecurityException`, a service-side exception AIDL re-throws), and `ArchiveSource` turns a
    provider stream's `RuntimeException` mid-copy into `Unreadable`: the contract "a failure
    value, never a thrown exception" applied one step wider than the design lists.
18. **`ArchiveInspectionResult.summaryOrNull`** is the one-field accessor `FylzV1App.kt:1012` uses
    so the Extract action's encryption check stays one line without a new import (the file sits on
    its 2,287-line ratchet); the design says `Ready.summary.hasEncryptedEntries` inline.

**Relevant commits:** `8e35497`, `1983c0c`, and the M3.2c commit that adds this entry.

**Risk if it turns out wrong:**
- Refusing pipes (1): a provider the default probe misjudges (a pipe reporting a non-negative
  `statSize`, or a regular file reporting -1) would either send the engine a pipe (`NOT_SEEKABLE`,
  logged as a bug, shown as "could not be opened") or stage a file that did not need staging
  (wasted copy, correct result). Both are visible, neither loses data.
- Rule-set disagreement (2): a ZIP the dialog calls extractable that `extractZip` then refuses
  with the Kotlin reason, or the reverse -- confusing, bounded, and gone in M3.4.
- The root-entry drop (13): if a format ever lists a *real* member named `.`, it is silently
  omitted from the listing and the policy never sees it; libarchive's readers do not produce one
  outside the two cases above, but a hostile archive could try (it would then not be extracted
  either, since `extract()` skips it the same way).
- No idle unbind (12): one idle isolated process per app process after the first inspection --
  memory the user pays for a browsing session; measured in M3.3.
- The 500-row cap (11): a preview that shows 500 rows of an 80,000-entry tarball and a note --
  by design, but a user may read it as a truncated archive.

## M3.3 — archive browsing as folders

**Milestone:** M3.3 (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` rev 2 plus the five coordinator
amendments from the M3.4 review; per-commit detail in `docs/agent/PROGRESS.md`'s `M3.3` row;
device checks in `docs/agent/DEVICE_CHECKS.md` section 18 once M3.3d lands). Not a gate:
log-and-continue. Commits: M3.3a (this entry; listing codec, ordinals, `extract_entry_at`,
streaming decoder client, catalog, entry cache, sweeper), then b (provider), c (UI and registry),
d (docs); each extends this entry.

**What was decided, and needs a second read** (the design's section 3 list, then what
implementation added):

1. Drag-out deferred to M12.2 (design section 0): no drag substrate exists; every entry now has a
   grantable `content://` Uri, which is the one thing drag needs.
2. In-archive search off until M8 (the recursive engine walks `DocumentsContract` trees).
3. RAR/CBR, `arj img dmg wim xar`, and single-file compressed streams (`notes.txt.gz`) are not
   browsable (section 2.5); the plan's table says "read: yes" for several.
4. Copy-out is per entry (materialise, then copy; about N/2 full decompressions for `tar.*` and
   solid 7z) until M3.4's one-pass bulk path (section 2.4).
5. Entries over 512 MiB cannot be opened in place ("Extract it instead").
6. Encrypted entries, links and special files do not open (section 2.4); hardlinks resolve.
7. Pipes as the one bulk channel; SELinux for passed *file* descriptors (read and write alike) is
   unanswered until section 18 item 7 (section 2.2).
8. Inactivity + archive-offset liveness instead of section 4.4's flat 30 s for streaming calls.
9. Section 4.4's "read-only descriptors, structure as Parcelables": `:decoders` now receives a
   writable pipe and structure crosses in a codec.
10. `location.kind` arrives before MC.2 with a two-value set (`FOLDER`, `ARCHIVE`; section 2.6).
11. No thumbnails inside archives (a thumbnail would be a fill per visible image).
12. Archives over 200,000 entries are not browsable; damaged archives list partially.
13. Listing memory in `:decoders` is the Rust `Vec` only (about 30 MB at the bound).
14. Opening entries of a policy-refused archive is refused; browsing is not.
15. Idle unbind after 60 s with nothing in flight (section 2.9; closes M3.2 item 12).
16. Breadcrumb separator stays `/` (the plan writes `›`).
17. A vanished archive behind a restored location toasts and stays; Up works.
18. Ids carry no listing key: a clipboard entry pasted after the archive was replaced copies the new
    archive's entry at that position (as a replaced file would).
19. `ACTION_SEND_MULTIPLE` of thousands of ~250-character Uris can hit the transaction limit and
    today's `runCatching` swallows it.
20. `.iso` browsing overlaps M4.3 / section C3's `disk-image` kind.
21. Fixtures live under `core/fixtures/archives/` and the golden `.fzl` files under
    `app/src/test/resources/fixtures/archives/` (MASTER_PLAN section 3.4 asks for both trees).
22. **Ordinal definition (amendment):** an entry's ordinal is the 0-based index of the raw
    `archive_read_next_header` call that returned it, counting every header -- the ISO/tar `.`
    root the engine drops, links, `Other` kinds -- never an index into the entry list; it lives on
    `EntryMetadata.ordinal` from one shared counter, so a `./`-rooted tar's first member is
    ordinal 1 (`dot-rooted.tar`). Document ids and M3.4's `Selection::Ordinals` use it.
23. **SIGPIPE (amendment):** a cdylib gets no `SIG_IGN` from Rust's runtime, so `fylz-ffi-android`
    ignores SIGPIPE once at the start of every exported archive function; a cancelled stream's
    `EPIPE` is an `Err`, not a dead `:decoders`. Android's runtime already ignores it in app
    processes (recorded, not relied on); the test proves it in a child process with `SIG_DFL`.
24. **Pin the source, never a descriptor (amendment, blocker-class):** a Binder-passed
    `ParcelFileDescriptor` shares the open file description and its offset; two concurrent engine
    calls on dups of one descriptor would interleave (`read` + `lseek(SEEK_CUR)` after a rewind) and
    silently corrupt tar/ISO/cpio reads. `PinnedSource` re-opens a descriptor per call
    (`openFileDescriptor` again, or `ParcelFileDescriptor.open` on the staged/materialised file);
    the design's "pins one `ArchiveSource.Resolved`" was that bug. The interleave test runs under
    Robolectric, where an in-process stub receives the very same descriptor object -- the device
    half is section 18.
25. **Summary sidecar and `structural_refusal` (amendment):** `<key>.summary.json` beside every
    `.fzl` (a disk-first load previously had no policy verdict, yet the entry cache refuses from
    it); a listing without a readable sidecar fails closed and is re-listed. Rust computes
    `structural_refusal` (the policy under `Limits::structural_only()`: sizes unbounded, ratio
    infinite) on `Inspection`, the FFI record, the Parcelable and the sidecar; M3.4 refuses
    extraction from an archive whose structural verdict is a refusal or unknown.
26. **`.` segments are no-ops (amendment; a deliberate deviation from the Kotlin parity rule, for
    the owner's review pass):** `validate_path`/`normalized_path_key` strip a leading `./`
    repeatedly and drop `.` segments before the remaining checks; `..` stays refused; a directory
    that is only `.`/`./` is the archive root. Closes M3.2 item 13. Every `tar -C dir -cf x.tar .`
    archive and every ISO would otherwise be refused whole and none of its entries openable.
    `folder/./file` left the Kotlin hostile corpus for the new allowed case.
27. The codec carries the engine's (lossily decoded) path string, not the raw non-UTF-8 bytes:
    Java's and Rust's replacement rules differ, and the byte-exact match must see the same string
    the id was built from (M3.7 gives both a charset).
28. `DecoderClient` retry-once applies to a transaction whose `RemoteException` arrived after
    another call's drop bumped the generation; a bind dropped while connecting stays `Failed` (the
    M3.2b test). A streaming call's `drain` may therefore run twice and must start over.
29. A failing `drain` (the sink's disk is full) closes both pipe ends so the engine's write gets
    `EPIPE`, and is thrown to the caller with the connection kept -- not the process's fault.
30. Handles are reference counted (`retain`/`release`) rather than closed on LRU eviction, so a
    fill in flight on an evicted archive completes; the source closes at zero.
31. Observations on the fixtures worth knowing before trusting the tree's rules: libarchive's tar
    reader reports directories with a trailing `/` whatever the header said (`tarfile` writes
    `dir`), and its ZIP reader already rewrites `\` to `/` (`backslash.zip` lists as
    `dir/file.txt`), so the tree's backslash rule only matters for a reader that does not.
32. `sample-entries.zip`'s `font.ttf` is an sfnt header only (not renderable); section 18 item 2
    previews a real font from a real archive.
33. Directories and implicit entries are refused by the entry cache with "Folders cannot be opened
    as files." (not in the design's refusal list).
34. The once-per-process cache sweep runs from `ArchiveCatalog.open` and `ArchiveInspector.inspect`
    (first wins); the design only said it moves out of `ArchiveSource.resolve()`.
35. `ArchiveExtractResult` is the minimal `outcome/message/bytesWritten`; M3.4 adds its counts.
36. **(b)** The provider's attach test uses the manifest's `<provider>` attributes read from the
    XML when Robolectric's package manager does not know the manifest (this project runs without
    `includeAndroidResources`); `attachInfo` still enforces the contract, and the test asserts
    there is no `DOCUMENTS_PROVIDER` intent filter. A device resolves the same info through the
    package manager -- worth one look at `dumpsys package io.github.mbaliga.fylz` (section 18).
37. **(b)** `DocNode.children` turns the archive provider's `EXTRA_ERROR` into an `IOException`
    too, so a directory copy-out of an archive that failed to list fails the operation rather than
    copying an empty folder; the design named only `DocumentRepository.listChildren`.
38. **(b)** `openDocument` honours a `CancellationSignal` by cancelling its fill; callers that pass
    none (`openInputStream`, Coil, the transfer engine) run to completion or failure, as designed.
39. **(c)** `FileFormatRegistry.archives` was extended with `lz4 tzst tar.lz4 cb7 warc` (and the
    `tar.lz4` compound) so that `EntryKind.ARCHIVE` classification agrees with
    `BrowsableArchiveFormats`; the design said the registry set "stays private", which it does. The
    browsable set itself has no `kind` precondition, so the two can still drift -- a test pins the
    browsable set at 27 members and names every excluded family.
40. **(c)** `ArchivePreview` (the renamed `ZipArchivePreview`) loads through `ArchiveCatalog`, not
    `ArchiveInspector.inspect`: one listing on disk serves the folder view and the preview, and the
    tree supplies the quarantined count and the partial message. The preview caps at 500 root
    children of the tree itself; the engine's `rowsTruncated` is no longer what it shows.
41. **(c)** `refresh()` calls `ArchiveCatalog.forgetFailures()`, so a pull-to-refresh retries a
    memoised catalog failure (a vanished or unreadable source) -- the design left the retry path
    implicit. Nothing else clears the memo before the process ends.
42. **(c)** `LocationKind.of(uri)` derives the kind from the Uri's authority in
    `buildBrowserState`; `FolderLocation` carries no kind field and `SessionCodec` is unchanged.
    MC.2 will need a richer source (tree roots for internal/sd/usb/network, the bin) than the
    authority for the remaining values.
43. **(c)** The dead `private fun fileIcon` in `FylzV1App.kt` (unreferenced since the entry row took
    `EntryThumbnail`) was removed together with the `BrowserState` extraction; the file is 2271 lines
    (from 2287) and the ratchet follows. The design estimated about 10 lines of saving.
44. **(c)** The design's `SessionCodecTest` restore-and-list case is the sibling class
    `SessionRestoreArchiveLocationTest` (it needs the hosted providers and the fake decoder).
45. **(c)** The Compose paths -- tap and double-tap into an archive, the breadcrumb, Up out of an
    archive, disabled actions in the menus, `ArchivePreview`, an entry previewed through its document
    Uri, search staying off inside an archive -- have no unit coverage (no Compose harness); the
    registry rows, the builder, the format set, the destination filter and the restore path do.
    Section 18 covers them on a device.

**Relevant commits:** `05c3531` (a); `9b1bceb` (b); `c7f6a86` (c); the M3.3d commit (docs:
`ARCHITECTURE.md` "Archives as documents", `DEVICE_CHECKS.md` section 18, this entry).

**Risk if it turns out wrong:**
- Ordinals (22): a listing and an `extract_entry_at` that count headers differently would fetch
  the wrong member; one shared counter and `NotFound` on a path mismatch are the guards, and a
  mismatch surfaces as "This entry is no longer in the archive", never as wrong bytes.
- Pinned source (24): if a provider hands back a *different* file on a re-open (a cloud provider
  refreshing), two fills could read two versions; the declared-size check catches a length change
  only. Staged and materialised sources are immutable files, so the risk is confined to
  `PinnedSource.Document`.
- The `.` rule (26): an archive that used `.` segments to smuggle nothing (there is nothing a `.`
  can smuggle) is now extractable; `..`, absolute and drive-qualified paths are unchanged.
- SIGPIPE (23): if the AndroidRuntime claim is wrong AND the `Once` failed to run before a write,
  a cancelled stream would kill `:decoders`; the client treats that as `Failed` and rebinds, so
  the failure mode is a wrong message, not a crash of the app.
- The sidecar (25): a stale sidecar with a fresh `.fzl` cannot happen (the sidecar is written
  after the rename and deleted with it), and a missing one re-lists -- the cost is one extra pass.
