# ADR: Linux/Ubuntu Touch strategy — sandbox realities and honest gaps for M13

**Status:** accepted, as a record of what was found, not a re-decision of `MASTER_PLAN.md`'s own
M13/M14 sections, which already made the real architecture calls (one QML UI shared between
Ubuntu Touch and desktop Linux; cxx-qt as the Rust↔Qt bridge; the freedesktop Trash spec on
Linux). This document's job, per the brief that produced it, is narrower: record why this work
started ahead of its own stated prerequisite, what M13.1 (this crate) actually built, and — since
the next dispatch (M13.2/M13.3) will need to trust or distrust some sandbox-availability claims
before it starts — what was actually verified in a real build sandbox on 2026-09-30, corrected
where that contradicts an earlier claim.
**Date:** 2026-09-30.
**Author:** the M13.1 dispatch (this crate, `fylz-ops`).
**Shape borrowed from:** Fotoz's sibling repo, `docs/adr/ADR-013-linux-strategy.md` (context →
decision → consequences), per the task brief's own instruction — its *content* is unrelated;
Fylz's own `MASTER_PLAN.md` already covers the equivalent ground for Fylz.

---

## 1. This work is ahead of schedule, on purpose, at the owner's request

`MASTER_PLAN.md` §5 states M13's prerequisite plainly: **"M2-M9 core crates are stable."** As of
this dispatch, that is not true. `PROGRESS.md` and `core/crates/*/src/lib.rs` both confirm the
real state:

| Crate | Milestone | State |
|---|---|---|
| `fylz-types`, `fylz-actions`, `fylz-archive`, `fylz-sniff` | M2 | done |
| `fylz-verify` | M4.6 | one-line stub: *"Hashing, checksum files, and OpenPGP signature verification (rpgp); implementation lands in M4.6."* |
| `fylz-rename` | M7 | one-line stub: *"Rule-based rename engine; implementation lands in M7."* |
| `fylz-query` | M8 | one-line stub: *"Query AST, typed syntax parser, NL rule parser and SQL builder; implementation lands in M8."* |
| `fylz-index` | M8 | one-line stub: *"Index schema plus FTS and vector ranking (rusqlite, sqlite-vec); implementation lands in M8."* |
| `fylz-clean` | M9 | one-line stub: *"Duplicate, similar/blurry-image and large/old-file detectors; implementation lands in M9."* |

M5 (encryption/crypto backend), M6, M10 (hardware), M11 (network) and M12 (workspace/UX) are
likewise not done. **M13.1 does not depend on any of M4, M5, M6, M7, M8, M9, M10, M11 or M12**,
and its own crate (`fylz-ops`, below) adds nothing to any of theirs. This dispatch is not a signal
that M4-M12 are finished, is not a claim that the M13 prerequisite is now met, and does not
authorise M13.2/M13.3 to assume any of those five crates has grown real logic since this was
written — check `core/crates/*/src/lib.rs` fresh rather than trusting this table's date.

**Why start anyway:** the owner explicitly asked for M13.1 on 2026-09-30, in full knowledge of the
gap above, accepting that it is ahead of schedule and cannot be fully device-verified (there is no
Ubuntu Touch device or UT-framework runtime in this sandbox — see §4). This ADR records that
decision; it does not relitigate it.

---

## 2. What M13.1 actually built

A new crate, `core/crates/fylz-ops` (added to `core/Cargo.toml`'s `[workspace] members`), porting
the Kotlin operation engine's journal, staging, verification, recycle bin, conflict and preflight
policies to Rust for the Linux/UT target. Full module-by-module detail, including exactly which
Kotlin file and test file each module ports from (or, for the freedesktop Trash backend, why
nothing is ported from at all), is in the crate's own `src/lib.rs` doc comment and each module's
own header — this section only summarises the shape and the two design calls the brief asked to
be recorded here.

**Crate name: `fylz-ops`.** Every sibling crate follows `fylz-<noun>` (`fylz-archive`,
`fylz-verify`, `fylz-rename`, `fylz-query`, `fylz-index`, `fylz-clean`, `fylz-sniff`, `fylz-usb`).
The Kotlin package this crate ports is `io.github.mbaliga.fylz.operations`; `fylz-operations`
was the literal choice, but every existing crate name is one short noun, and "ops" is the
unambiguous short form (it does not collide in meaning with anything else in this workspace).

**The freedesktop Trash implementation (`recycle::trash`).** No existing Rust code in this
repository implements, or references, the freedesktop.org Trash specification — a search for
`trash`/`XDG_DATA_HOME`/`xdg` across `core/` turned up nothing (the hits in `docs/` and the
Kotlin `app/` tree are all about Android's own, differently-shaped `.fylz-trash` SAF folder, a
different thing with the same English word). The closest existing Rust code is
`storage/VolumeInfo.kt`'s `/proc/self/mounts` parser (`parseMounts`/`filesystemTypeForPath`) —
useful precedent for *reading* mount information, but it solves a different problem (a path's
filesystem *type*) than what `recycle::trash::topdir_for` needed (a path's mount *boundary*),
which this crate computes independently by walking `st_dev` across ancestor directories rather
than parsing `/proc/mounts` text. So `recycle::trashinfo` (the `.trashinfo` file format: percent
encoding, the local-time `DeletionDate`) and `recycle::trash` (directory resolution per the
specification's home/topdir split, the `$topdir/.Trash/$uid` sticky-bit/symlink/ownership checks,
`$topdir/.Trash-$uid` fallback, trash/restore/purge/list) are written directly from the
specification text, not ported from anything — see `recycle/trash.rs`'s own doc comment for the
full reasoning, including why this design needs **no separate `RecycleBinStore` equivalent** at
all (the `.trashinfo` file next to each trashed item's content already durably records what
Android's SAF trash needed a separate JSON manifest for), and why restoring to a destination on a
*different* device than the trash directory is deliberately out of scope (that needs a real
copy-and-verify transfer, which belongs to a future multi-device copy/move engine, not the
recycle bin — this crate's own `checksum`/`staging` modules already provide the pieces such an
engine would use).

**The `ChecksumVerification`-vs-`fylz-verify` boundary.** Read both before deciding, per the
brief's own instruction to confirm rather than assume this split:
`ChecksumVerification.kt` (ported here as `checksum::verify_checksum`/`sha256_hex`) computes a
streamed SHA-256 of a transfer's *destination* against its *source*, both written and read by
this app in one copy/move operation, to catch silent corruption a same-length check would miss.
`fylz-verify`'s own doc comment describes a materially different job: hashing a file the user
already has (typically just downloaded) against a *publisher-supplied* checksum file or OpenPGP
signature, landing in M4.6. There is no publisher, checksum file or signature on either side of
`ChecksumVerification`'s own use — the two never call into each other, and `fylz-verify` staying a
stub blocks nothing here. The brief's framing held up under inspection; `crate::checksum` stays in
`fylz-ops`, not `fylz-verify`.

---

## 3. Corrected: what is actually available in this build sandbox

This section exists because the brief handed to this dispatch stated three specific sandbox
facts, framed as the reason M13.2/M13.3 will need to substitute Qt 6 for Qt 5.15 and plain
QtQuick Controls 2 for real Lomiri Components. Before writing this document, each of those three
claims was independently re-tested in this session's actual build sandbox (Ubuntu 24.04.4 LTS,
"Noble"), on 2026-09-30. **Two of the three claims do not hold here, and the third is real but was
mischaracterised.** Recorded plainly, per this task's own brief ("do not silently pretend... name
it plainly"), rather than repeating the earlier claims unchecked.

### 3.1 Qt 5.15 — claimed unavailable; found available

The brief stated `apt-cache search qtdeclarative5-dev qtbase5-dev` returns nothing on this base.
Reproduced first: true, on a container with no populated package index. Once `apt-get update` is
run (never run in whatever session produced the original claim), the picture is different:

```
$ apt-cache policy qtbase5-dev qtdeclarative5-dev
qtbase5-dev:
  Candidate: 5.15.13+dfsg-1ubuntu1       (noble/universe)
qtdeclarative5-dev:
  Candidate: 5.15.13+dfsg-1ubuntu0.1     (noble-updates/universe)
```

`apt-get install --dry-run qtbase5-dev qtdeclarative5-dev` resolves cleanly with no conflicts.
Qt 6 (`qt6-base-dev` 6.4.2, `qt6-declarative-dev` 6.4.2) is *also* installable, exactly as the
brief said — both are true; only the "Qt 5.15 is unavailable" half was wrong. **The lesson is the
apt cache, not the archive:** `apt-cache search`/`policy` on a fresh container needs `apt-get
update` first, or an empty index looks identical to "not packaged."

### 3.2 Real Lomiri Components — claimed unavailable outside clickable's Docker environment; found available via plain apt

```
$ apt-cache policy qml-module-lomiri-components
qml-module-lomiri-components:
  Candidate: 1.3.5100+dfsg-1build2   (noble/universe, source: lomiri-ui-toolkit)
```

`apt-get install --dry-run qml-module-lomiri-components` resolves cleanly, pulling in
`lomiri-ui-toolkit-theme`, `qml-module-lomiri-components-labs`, `qml-module-lomiri-performancemetrics`
and the rest of the real Lomiri UI Toolkit — depending on `libqt5qml5 (>= 5.12.8)`, confirming
Lomiri Components are themselves a Qt 5 toolkit in Debian/Ubuntu's own packaging (not yet built
against Qt 6 there), which is itself a real, useful data point for the Qt-version decision below.
**No Docker, clickable, or UBports SDK is needed to install and locally exercise the real Lomiri
QML components on this sandbox's base image.** What real Lomiri Components on plain apt do *not*
give you is a cross-compiled, framework-matched `.click` package for an actual arm64 UT device —
that packaging step is a separate concern from whether the components exist at all, addressed
next.

### 3.3 The clickable Docker images — claimed 401/access-denied; found public and reachable, but this sandbox has no running Docker daemon at all

`docker manifest inspect clickable/ubuntu-sdk` (a guess at the image name) does return
`denied: requested access to the resource is denied` — reproduced, and this is where the brief
stopped. But Docker Hub returns that exact message for both "this repository doesn't exist" and
"this repository is private," indistinguishably, as an anti-enumeration measure — so a denied
response to a guessed name proves nothing on its own. Querying Docker Hub's own public API for
the real `clickable` namespace:

```
$ curl -s "https://hub.docker.com/v2/repositories/clickable/?page_size=100" | ...
clickable/amd64-ut24.04-2.x-arm64      private=False   # exactly the plan's own target framework
clickable/amd64-ut26.04-1.x-arm64      private=False   # exactly M13.7's own Qt-6-readiness contingency
... (81 repositories total, all private=False)
```

```
$ docker manifest inspect clickable/amd64-ut24.04-2.x-arm64
{ "schemaVersion": 2, ... }        # succeeds, no authentication, exit 0
```

The registry is fully public and reachable, including the exact framework versions
`MASTER_PLAN.md`'s own M13 research and M13.7's contingency name. **The real constraint in this
sandbox is different from what was claimed: there is no Docker daemon running at all** —
`dockerd` is installed but not started, there is no `/var/run/docker.sock`, and this container has
no init system (`systemctl`/`service` both fail: "System has not been booted with systemd") to
start one. `docker pull`/`docker run` — what `clickable` actually needs to do anything — fail with
a connection error to the daemon socket, not an authentication error to the registry. Whether a
future sandbox for the M13.2/M13.3 dispatch has a running daemon (a different container profile,
Docker-outside-of-Docker, or a privileged runner) is unknown from here and should be checked
directly, in under a minute, the same way this section's claims were checked.

### 3.4 What this means for the next dispatch

None of the above is this dispatch's decision to make — M13.2/M13.3 (the `fylz-ffi-qt` bridge and
the QML UI) are explicitly out of scope here, and the actual choice needs more than an apt-cache
check (whether cxx-qt's own Qt 5.15 support is solid in practice, whether the real Lomiri
Components QML behaves acceptably off-device, whether a Docker daemon is reachable by whatever
means when packaging time comes). What this ADR owes that dispatch is an accurate starting point,
not a stale one:

- **Do not assume Qt 6 / plain QtQuick Controls 2 is sandbox-forced.** It may still be the right
  choice — `MASTER_PLAN.md` §5 already prefers sharing one QML codebase with desktop Linux (M14),
  Fotoz's own `ADR-013` independently reached the same "one Qt 6 shell, no Lomiri-specific chrome"
  conclusion for a different app, and M13.7 already scopes a Qt 6 branch as a *named contingency*
  — but that choice should be made on those merits, not on an unavailability claim this ADR just
  disproved.
- **Re-verify at the start of M13.2, in that dispatch's own container**, exactly the three checks
  in §3.1-§3.3 above (`apt-get update` then `apt-cache policy`, and a Docker daemon/registry
  check) — takes under a minute, and containers can differ from this one.
- **If a working Docker daemon is available there**, the real `clickable` images for both the
  plan's stated UT 24.04-2.x target and M13.7's UT 26.04-1.x contingency exist today and are
  pullable anonymously; local QML development against real Lomiri Components does not need Docker
  at all, only `apt-get install qml-module-lomiri-components` (and Qt 5.15, also apt-installable).

---

## 4. What M13.2's planned `QObject`s can be real today, given M4/M7/M8/M9's actual state

`MASTER_PLAN.md` §5, M13.2, lists six `QObject`s the `fylz-ffi-qt` bridge is to expose. Given §1's
table above (unchanged by this dispatch):

| `QObject` | Backing crate | Can be real today? |
|---|---|---|
| `ArchiveModel` | `fylz-archive` (M3, done) | **Yes — confirmed real and running (M13.2, below).** |
| `FolderModel` | filesystem + this crate (`fylz-ops`) | **Yes — confirmed real and running (M13.2, below).** |
| `OperationQueue` | this crate (`fylz-ops`) | **Yes — confirmed real and running (M13.2, below).** |
| `RenameController` | `fylz-rename` (M7, stub) | **No — confirmed stubbed (M13.2, below).** `fylz-rename` is a one-line doc comment; a `RenameController` today would have nothing to call. |
| `SearchController` | `fylz-query`/`fylz-index` (M8, stubs) | **No — confirmed stubbed (M13.2, below).** Same situation — no query AST, no index schema exist yet. |
| `PreviewProvider` | thumbnail generation (not yet built anywhere in `core/`) | **Partial (M13.2, below).** Real content classification via `fylz-sniff`; no pixel decode. |

**The honest recommendation for M13.2:** build `ArchiveModel`, `FolderModel` and `OperationQueue`
with real backing logic now — the crates behind them exist and are tested. Stub
`RenameController` and `SearchController` honestly (a `QObject` that exists in the bridge's shape
but returns "not yet implemented" / an empty result, clearly marked, matching this repository's
own established convention of a one-line stub crate stating plainly what lands later) rather than
faking rename or search behaviour against nothing. `PreviewProvider` needs its own scoping
decision before M13.2 starts, not a guess folded into it.

---

## 5. Consequences

- `core/Cargo.toml`'s `[workspace] members` gained `crates/fylz-ops`; every other crate is
  unchanged.
- No Android code, and no other Rust crate, was touched. `fylz-ops` depends on nothing else in
  this workspace and nothing else depends on it yet — M13.2 is the first crate that will.
- This ADR is not a gate and blocks nothing; it is the record §0 above exists to leave. The
  §3 corrections should be treated as this dispatch's most load-bearing finding for whoever picks
  up M13.2 next — verify fresh regardless, but start from an accurate baseline instead of a stale
  201/403 story that does not hold in this sandbox.

---

## 6. M13.2 + a minimal M13.3 slice: what actually got built, real vs. stubbed, confirmed running

This section is the record §0/§4 above point forward to, written by the M13.2 dispatch (this
crate's own real content, `core/crates/fylz-ffi-qt`) on 2026-09-30, immediately after M13.1. Per
this dispatch's own brief, §3's two corrections (Qt 5.15 and real Lomiri Components both
apt-installable; no running Docker daemon) were re-verified independently in THIS session's own
container before relying on either — both held, unchanged from §3's record.

### 6.1 cxx-qt version pinned: 0.7.3

The brief asked for a cxx-qt version confirmed to support Qt 5.15, not an assumption that the
latest one does. Checked two ways before picking: `cargo search cxx-qt` in this sandbox shows the
newest published version is 0.10.0 (crates.io publish timestamps in this workspace's own view of
time run through 2026-08-24, consistent with this sandbox's other dated findings), and the
`cxx-qt` project's own current documentation states plainly that it targets "the Qt versions that
have official support by the Qt company... Qt 5.15 LTS and all versions of Qt 6" — i.e. every
current release line still claims Qt 5.15 support, not just an old one. Rather than trust that
claim for whichever version happened to be newest, this dispatch **built a real, minimal cxx-qt
QObject bridge against this sandbox's actual Qt 5.15.13 with cxx-qt 0.7.3 specifically** (a
stand-alone probe crate first, then this crate itself) and it built and ran end to end — see §6.4.
0.7.3 is not the newest (0.8.0-0.10.0 exist), but it is the one this dispatch actually proved,
so it is the one `Cargo.toml` pins, rather than a guess at a newer line's real Qt 5.15 behaviour.

### 6.2 Real vs. stubbed, per `QObject` (adding to §4's table above)

- **`FolderModel`, `ArchiveModel`, `OperationQueue` — real, confirmed running end to end.**
  `FolderModel` lists a real directory via `std::fs`, and its `hasConflict`/`isStaging` columns
  are genuine `fylz_ops::preflight::PreflightPolicy::evaluate`/`fylz_ops::staging::is_staging_name`
  verdicts, not a hand-rolled duplicate check. `ArchiveModel` lists a real archive via
  `fylz_archive::inspect` (M3.3's own browsing entry point). `OperationQueue` lists a real
  `fylz_ops::journal::Journal`'s operations, including a genuine reconcile-after-process-death
  sweep (§6.4). Each is a thin `#[cxx_qt::bridge]` `QAbstractListModel` wrapper
  (`src/folder_model.rs`, `src/archive_model.rs`, `src/operation_queue.rs`) around one Qt-free
  plain-Rust function in `src/logic.rs`, unit-tested there (9 tests, §6.5) independently of
  whether Qt is even installed.
- **`PreviewProvider` — a documented partial stub, not the full `QQuickImageProvider`.** It
  classifies a file's real content via `fylz-sniff` (M2.5, done: genuine magic-byte sniffing of
  the file's own bytes) but produces no actual thumbnail pixel. The master plan's own M13.2 entry
  names this `QQuickImageProvider` specifically; that Qt class is a plain, non-`QObject` abstract
  C++ interface (registered on a `QQmlEngine` via `addImageProvider`, not instantiated as a QML
  element), outside cxx-qt 0.7.3's supported subclassing surface, which targets a `QObject`-
  derived base (`#[base = QAbstractListModel]`, used by the three models above) — not
  `QQuickImageProvider`. Hand-writing a real C++ image provider on top of this bridge is real,
  in-scope future work (M13.3's full UI or a follow-on dispatch), not attempted here — no new
  Rust image-decoding dependency was added either (checked: no existing crate in `core/` pulls one
  in; `fylz-sniff` already gives real classification for free). Said plainly per this task's own
  brief, rather than silently narrowing scope.
- **`RenameController`, `SearchController` — confirmed stubbed, and why.** `fylz-rename` (M7) and
  `fylz-query`/`fylz-index` (M8) are still the exact one-line stub crates §1's table already
  recorded — re-checked fresh for this dispatch, unchanged. Each `QObject` exists with its real
  shape (`available: bool` property, always `false`; one invokable QML code can already compile
  against) so QML written against them today does not need to change once M7/M8 land, but every
  actual call returns a string naming the specific stub crate and milestone rather than
  fabricating a rename or search result.

### 6.3 Why this crate cannot break `.github/workflows/android.yml`'s existing CI

That workflow's `cargo test --workspace` / `cargo clippy --all-features --all-targets --workspace`
step (from `core/`, M2.6) runs on a plain `ubuntu-latest` GitHub Actions runner with no Qt
installed at all, and this task's own brief says the Android side stays untouched. Since
`cxx-qt-build`'s Qt/C++ discovery panics outright when it finds no Qt, simply adding
`fylz-ffi-qt` as a real workspace member with an unconditional `CxxQtBuilder::build()` call would
have broken that CI the moment this crate stopped being a one-line stub. `build.rs` guards
against this itself: it probes for `qmake` on `PATH` BEFORE ever calling into `cxx-qt-build`, and
sets a `fylz_qt_available` cfg only when Qt was actually found. Every Qt-touching module in
`src/lib.rs` is gated on that cfg — not stubbed, literally absent from the compiled crate when
unset — so on a Qt-less machine this crate compiles to an inert placeholder (`src/lib.rs`'s own
doc comment) instead of panicking the build. `src/logic.rs` (the real business logic every
`QObject` wraps) carries no such gate and always compiles and tests, Qt or not. This sandbox DOES
have Qt, so every check in this dispatch's own gate (§6.5) ran against the real bridge, not the
placeholder.

### 6.4 The QML end-to-end proof: exactly what was run, and what it showed

Per this task's brief, "compiles" was not treated as good enough — the QML screen was actually
launched and driven. Reproducible from `core/`, after `apt-get install qtbase5-dev
qtdeclarative5-dev qtdeclarative5-dev-tools qml-module-lomiri-components` (§3.1/§3.2, re-verified):

```
$ cargo build -p fylz-ffi-qt --bins
$ ./target/debug/fylz-seed-demo > /tmp/fylz_demo_env.sh   # a real, separate OS process: creates
                                                            # a real temp folder (real files, a
                                                            # real case-collision, a real
                                                            # .fylz-part-* staged write, a real
                                                            # PNG-signature file) and a real
                                                            # fylz_ops journal with one operation
                                                            # deliberately left `Running`
$ source /tmp/fylz_demo_env.sh
$ QT_QPA_PLATFORM=offscreen ./target/debug/fylz-qml-demo   # a SECOND, genuinely different OS
                                                            # process: loads qml/main.qml via a
                                                            # real QGuiApplication +
                                                            # QQmlApplicationEngine
```

Real, observed output (unedited console lines from this exact run):

```
FYLZ_FOLDER_ROWCOUNT 6
FYLZ_FOLDER_ROW 0 name=.fylz-part-demo-op-0-incoming.bin isDirectory=false sizeBytes=512 isStaging=true hasConflict=false
FYLZ_FOLDER_ROW 4 name=report.TXT isDirectory=false sizeBytes=4 isStaging=false hasConflict=true
FYLZ_FOLDER_TOTALBYTES 4710
FYLZ_ARCHIVE_ROWCOUNT 48
FYLZ_ARCHIVE_ROW 0 path=photos/ isDirectory=true sizeBytes=0
FYLZ_ARCHIVE_FORMAT ZIP 2.0 (uncompressed)
FYLZ_QUEUE_ROWCOUNT 3
FYLZ_QUEUE_ROW 2 id=84309a28-... kind=Copy state=NeedsAttention itemCount=1 progressPercent=25
FYLZ_PREVIEW PNG image (image/png) -- classified via fylz-sniff; NOT_IMPLEMENTED: pixel thumbnail rendering ...
FYLZ_RENAME NOT_IMPLEMENTED: fylz-rename (M7) is still a one-line stub crate ...
FYLZ_SEARCH NOT_IMPLEMENTED: fylz-query/fylz-index (M8) are still one-line stub crates ...
```

Every number is real, not asserted-then-hidden: 6 real folder rows (matching exactly what
`fylz-seed-demo` wrote, byte-for-byte sizes included — 512+4+4096+32+4+62 = 4710), the real
case-insensitive collision flagged on the SECOND-seen name only (`report.TXT`, not `Report.txt`,
matching `PreflightPolicy`'s own documented first-seen rule), 48 real entries from
`core/fixtures/archives/tree.zip` (an existing M3 fixture, not fabricated for this task) with the
real ZIP format string libarchive itself reports, and — the most load-bearing single line —
`state=NeedsAttention` at 25% progress on the operation `fylz-seed-demo` left `Running`:
`fylz_ops::journal::Journal::open`'s reconcile-after-process-death sweep firing for real, because
`fylz-qml-demo` really is a different OS process than the one that wrote it, not a simulation of
one. The process exited 0 on its own (a `Qt.callLater(Qt.quit)` chain in `qml/main.qml`), needing
none of the 30 s timeout budgeted for it.

Two real bugs surfaced and were fixed in the course of getting this to run, recorded because they
are genuinely load-bearing cxx-qt 0.7.3 facts the next dispatch should not have to rediscover:

- **cxx-qt does not camelCase Rust snake_case names.** A `#[qproperty(i64, total_bytes)]` becomes
  a literal `Q_PROPERTY(... total_bytes ...)`, and `fn row_summary(...)` becomes
  `Q_INVOKABLE ... row_summary(...)` — QML calling `.totalBytes`/`.rowSummary(...)` (idiomatic Qt
  style) got `undefined`/`TypeError`. Fixed with explicit `cxx_name = "totalBytes"` /
  `#[cxx_name = "rowSummary"]` on every multi-word property and invokable.
- **An overridden virtual loses the base class's C++ default argument.** Qt's own
  `QAbstractItemModel::rowCount(parent = QModelIndex())` has a default; the generated C++
  override of a `#[cxx_override]` `row_count(self, _parent: &QModelIndex)` does not carry that
  default forward, so QML script calling `model.rowCount()` with zero arguments (rather than a
  `ListView` delegate's own internal C++ dispatch, which never goes through this path) failed with
  "Insufficient arguments". Worked around with a separate, genuinely zero-argument `count()`
  invokable on each model, kept alongside the real `rowCount()` override rather than instead of
  it.
- **A `[[bin]]` in the same package as the bridge does not link unless it references the `[lib]`
  crate.** `cxx-qt-build`'s Qt/C++ link flags apply to every target in the package, but the
  compiled Rust-side definitions of each `extern "Rust"` glue function live only in the `[lib]`
  crate's own object code; a `[[bin]]` whose source never references that crate is never linked
  against its `.rlib` at all, leaving the C++ side's required symbols undefined. Fixed by giving
  `src/lib.rs` a real `run_qml_demo()` function `src/bin/qml_demo.rs`'s `main` genuinely calls
  (not a dummy reference), and having `src/bin/seed_demo.rs` genuinely check
  `fylz_ffi_qt::QT_UNAVAILABLE_AT_BUILD_TIME` before printing its exported paths.

### 6.5 Gate, run from `core/`

`cargo test --workspace`: 296 passed (287 before this dispatch + 9 new, all in `fylz-ffi-qt`'s
`logic` module — Qt-free, so they run regardless of Qt's own availability), 0 failed.
`cargo clippy --workspace --all-targets -- -D warnings` and
`cargo clippy --all-features --all-targets --workspace -- -D warnings` (the exact command
`android.yml` runs): both clean. `cargo fmt --check`: clean. `cargo deny check`: `advisories ok,
bans ok, licenses ok, sources ok` — the new cxx/cxx-qt/cxx-qt-lib/cxx-qt-build dependency tree
(all MIT OR Apache-2.0, KDAB's own convention) needed no `deny.toml` allow-list change at all;
the only new warnings are the same shape of pre-existing duplicate-version/license-not-encountered
informational warnings every prior commit already carries (now also naming `thiserror`/
`unicode-width`, from this new tree), not failures.

### 6.6 Packaging (M13.5, scoped down): `ut/clickable.yaml`, `ut/manifest.json`, `ut/fylz.apparmor`

Written as source only, per this task's own brief — never invoked, since §3.3's Docker-daemon
finding still holds (re-checked fresh: `dockerd` still has no running daemon here). Modelled on
Clickable 8.9+'s own documented project-config schema and a real production app's own files
(UBports' `lomiri-filemanager-app`, whose `filemanager.apparmor` is the source for
`"template": "unconfined"` — the exact real-world precedent `MASTER_PLAN.md`'s own research note
names: "the stock `lomiri-filemanager-app` already has" the unconfined template and its "Full
System Access" badge), not invented from scratch. `ut/clickable.yaml`'s `root_dir` points at
`core/crates/fylz-ffi-qt` — the one crate M13.2 built, which (via `cxx-qt-build`'s `qml_module`)
already **is** the app in the same shape as cxx-qt's own `cargo_without_cmake` example, so there
is no separate "app" crate to package. **Not yet buildable, stated plainly in the file's own
comments:** `specific_output_bin: fylz` names M13.3's own future full app binary, which does not
exist yet (only this crate's dev-only `fylz-qml-demo`/`fylz-seed-demo` proof harnesses do) — this
project cannot actually run `clickable build` successfully until M13.3 adds it, on top of needing
a working Docker daemon this sandbox still does not have.

**Scoped down from the full M13.5, on purpose:** this dispatch's own brief narrows M13.5 to
exactly `clickable.yaml` + `manifest.json` + the AppArmor manifest, and says nothing about the
plan's further M13.5 item, a second confined "Fylz Lite" variant using `content_exchange` only.
That variant is real, separate, still-unstarted work (it needs its own `manifest.json`/apparmor
pair and, more fundamentally, a `content_exchange`-only code path M13.3's full UI has not been
built yet to provide) — not attempted here, rather than a half-built or guessed-at second
variant. M13.6 (the OpenStore submission kit) is untouched for the same reason this task's brief
states directly: it is gated on Madhav submitting (`GATE-UT`) and needs a real built `.click` to
write meaningfully about, which this sandbox cannot produce.

