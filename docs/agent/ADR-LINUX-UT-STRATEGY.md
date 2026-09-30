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
| `ArchiveModel` | `fylz-archive` (M3, done) | **Yes.** Real archive read/write/extract logic exists now. |
| `FolderModel` | filesystem + this crate (`fylz-ops`) | **Yes.** Ordinary directory listing plus this crate's preflight/conflict/checksum/recycle policies are real, working Rust today. |
| `OperationQueue` | this crate (`fylz-ops`) | **Yes.** `journal::Journal`'s claim/tag/reconcile lifecycle, `preflight`, `conflict`, `checksum` and `recycle` are real, tested Rust behind it. |
| `RenameController` | `fylz-rename` (M7, stub) | **No.** `fylz-rename` is a one-line doc comment; a `RenameController` today would have nothing to call. |
| `SearchController` | `fylz-query`/`fylz-index` (M8, stubs) | **No.** Same situation — no query AST, no index schema exist yet. |
| `PreviewProvider` | thumbnail generation (not yet built anywhere in `core/`) | **No**, and not listed in §1's table because no crate is even named for it yet in `MASTER_PLAN.md`'s M2-M12 sections; M13.2 would need to scope where that logic lives first. |

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
