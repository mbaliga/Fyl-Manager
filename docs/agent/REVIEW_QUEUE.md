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
- **Correction to M2.4's timeout contract (`c9b184a`, 2026-09-25, found by the M3.2 design review):** the per-call timeout in `DecoderClient` did not abandon a hung Binder call -- `withTimeoutOrNull { withContext(IO) { … } }` waits for the blocking body, so the unbind that lets the platform reap `:decoders` ran only after the native call returned. Section 4.4's "kill-and-restart on a timeout" was therefore not true between `6de3490` and `c9b184a`; nothing shipped called the client in that window, so no user-visible behaviour existed to regress. Fixed by running each transaction in a client-owned job outside the caller's scope, with elapsed-time tests. Two smaller pre-existing points the fix left alone, for the review pass: `DecoderClient.pending` is a non-volatile field shared between the main-thread `ServiceConnection` callbacks and the calling coroutine, and a `dropConnection()` racing an in-flight `ensureConnected()` can surface as a `CancellationException` rather than the documented `null`. Both are in scope for M3.2b, which makes the first real calls.
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
  escapes are refused" literally; a per-entry skip-with-warning would be the alternative.
- **Lossy names (section 6.1; lands with 3b's `inspect`).** A non-UTF-8 entry name (legacy
  CP437/GBK ZIPs) is decoded with `String::from_utf8_lossy` and flagged `name_lossy = true`
  instead of failing the whole inspection with `NonUtf8Path`; the policy validates the lossy
  string (its structural rules read the same through replacement characters). `read_entry(fd,
  path)` keeps `NonUtf8Path`, since an exact-match lookup on a lossy name is ambiguous; M3.7 gives
  both a real charset. Worth confirming that a lossy name reaching the UI in M3.2 is acceptable as
  an interim display, and that two distinct raw names collapsing to the same lossy string (both
  then refused as duplicates by the policy) is the intended conservative outcome.
- Smaller ported-semantics choices, made for parity rather than taste: name lengths are measured in
  UTF-16 code units (Kotlin's `String.length`), not bytes -- a filesystem's `NAME_MAX` is in
  bytes, so a 255-character non-ASCII name that passes here could still fail at SAF; and
  "blank" uses Java's whitespace set, not Unicode `White_Space` (they differ only at U+0085 and
  U+001C..U+001F).

**Relevant commits:** (this commit) -- M3.1 part 3a (policy, parity tests, `policy_evaluate` fuzz
target); part 3b (`extract()`, `inspect()`, `archive_entries` fuzz target) follows.

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
