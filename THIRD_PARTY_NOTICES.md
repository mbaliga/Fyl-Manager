# Third-party notices

Fylz is licensed under Apache License 2.0. The application depends on third-party software and platform services with their own licences or terms.

This notice is reconciled against the generated `releaseRuntimeClasspath` graph produced by the Release readiness workflow. The graph remains the authoritative inventory for a specific build because Gradle may resolve or upgrade transitive modules over time.

## Apache License 2.0 families

The resolved release graph contains the following open-source families distributed under Apache License 2.0:

- Kotlin standard library, Kotlin coroutines and Kotlin serialization
- JetBrains annotations and JetBrains Compose/AndroidX compatibility modules
- AndroidX Activity, Annotation, AppCompat, Architecture, Collection, Compose, Core, DocumentFile, Lifecycle, Room, SavedState, SQLite, Startup, WorkManager and their transitive AndroidX modules
- AndroidSVG
- Accompanist Drawable Painter
- Coil
- OkHttp and Okio
- zip4j
- JSpecify annotations
- `javax.inject`
- Guava `listenablefuture`
- JNA (`net.java.dev.jna:jna`) — dual-licensed Apache-2.0/LGPL-2.1; the Apache-2.0 option applies
  here (M2.2, for the generated `fylz-core` uniffi bindings)
- Kotlin coroutines Android (`org.jetbrains.kotlinx:kotlinx-coroutines-android`, M2.2)

The exact artifacts and versions are available in the `release-runtime-dependencies` workflow artifact.

Copyright notices and licence texts must be preserved according to each project's distribution requirements. Fylz's packaging configuration removes duplicate `META-INF` licence resources from the APK to avoid Android packaging collisions; that technical exclusion does not change or waive any licence obligation.

## Google-distributed SDKs and services

The resolved graph also contains Google-distributed components pulled by the document scanner integration, including:

- Google Play services base, basement and tasks
- Google Play services ML Kit Document Scanner
- ML Kit common components
- Firebase component/encoder annotations and runtime components
- Google Data Transport runtime components

These artifacts are governed by the applicable Google APIs, SDK and service terms and any notices shipped with the artifacts. They are not relicensed by the Fylz Apache licence.

The scanner is invoked only after an explicit user action. Availability and implementation can depend on the device and Google Play services.

## Rust core (`core/`)

The `core/` Rust workspace's own dependency graph is tracked separately by `core/deny.toml`
(cargo-deny), which enforces the licence allow-list in `docs/agent/MASTER_PLAN.md` section 2.2.
Notable dependencies as of M2.2:

- uniffi 0.32.2 (`core/crates/fylz-ffi-android`) — MPL-2.0, file-level copyleft, permitted
  anywhere per section 2.2. Generates the Kotlin bindings in
  `app/src/main/java/io/github/mbaliga/fylz/core/`.
- libarchive 3.8.9 (`core/third_party/libarchive`, a git submodule pinned to release tag `v3.8.9`,
  M3.1) — statically linked into `fylz-archive`, never a Cargo dependency. Licensed per its own
  `COPYING`: the library proper is 2-clause BSD (Copyright Tim Kientzle); `archive_read_support_
  filter_compress.c`, `archive_write_add_filter_compress.c` and `mtree.5` also carry a 3-clause UC
  Regents copyright; `archive_parse_date.c` is public domain; the BLAKE2 sources
  (`archive_blake2*.{h,c}`) are triple-licensed CC0-1.0/OpenSSL/Apache-2.0; the build scripts carry
  varying terms of their own. See `docs/agent/REPORT-M2.md` section 6 for the full breakdown.
- lz4 1.10.0 (`core/third_party/lz4`, a git submodule pinned to release tag `v1.10.0`, commit
  `ebb370ca83af193212df4dcbadcc5d87bc0de2f0`, M3.1 part 2a) — statically linked into `fylz-archive`
  as libarchive's lz4 filter backend, never a Cargo dependency. Only `lib/` is compiled (`lz4.c`,
  `lz4hc.c`, `lz4frame.c`, `lz4file.c`, `xxhash.c`), and per the root `LICENSE` everything under
  `lib/` is **BSD-2-Clause** (`core/third_party/lz4/lib/LICENSE`, Copyright (c) 2011-2020, Yann
  Collet). The GPL-2.0-or-later `programs/` tree (`programs/COPYING`) is never built
  (`LZ4_BUILD_CLI=OFF`); `build/cmake/CMakeLists.txt` is CC0 and only drives the build.
- zstd (Zstandard) 1.5.7 (`core/third_party/zstd`, a git submodule pinned to release tag `v1.5.7`,
  commit `f8745da6ff1ad1e7bab384bd1f9d742439278e99`, M3.1 part 2b) — statically linked into
  `fylz-archive` as libarchive's zstd filter backend, never a Cargo dependency. Only `lib/` is
  compiled (`ZSTD_BUILD_PROGRAMS=OFF`, `ZSTD_BUILD_CONTRIB=OFF`, `ZSTD_BUILD_TESTS=OFF`,
  `ZSTD_LEGACY_SUPPORT=OFF`, `ZSTD_MULTITHREAD_SUPPORT=OFF`). Zstandard is dual-licensed,
  **BSD-3-Clause** (`core/third_party/zstd/LICENSE`, Copyright (c) Meta Platforms, Inc. and
  affiliates) **or** GPL-2.0 (`core/third_party/zstd/COPYING`), every `lib/` source header
  offering "You may select, at your option, one of the above-listed licenses". **Fylz elects the
  BSD-3-Clause licence** for its use of Zstandard; the GPLv2 option is not exercised (section 2.2:
  GPL never enters the core app). `COPYING` stays in the submodule only because it is part of the
  upstream tree.
- zlib 1.3.2 (`core/third_party/zlib`, a git submodule pinned to release tag `v1.3.2`, commit
  `da607da739fa6047df13e66a2af6b8bec7c2a498`, M3.1 part 2c) — statically linked into `fylz-archive`
  as libarchive's gzip filter backend and its ZIP deflate (method 8) decoder, never a Cargo
  dependency, and used in preference to the zlib the Android NDK sysroot ships for every ABI
  (`build.rs` fails the build unless libarchive was configured against the vendored copy, so the
  host and all three ABIs carry this same zlib). Only the library proper is compiled
  (`ZLIB_BUILD_SHARED=OFF`, `ZLIB_BUILD_STATIC=ON`, `ZLIB_BUILD_TESTING=OFF`; every `contrib/`
  option defaults off, so `contrib/minizip` and its separate `LICENSE.Info-Zip` are never built).
  **zlib licence** (`core/third_party/zlib/LICENSE`, Copyright (C) 1995-2026 Jean-loup Gailly and
  Mark Adler): the origin must not be misrepresented, altered versions must be plainly marked, and
  the notice may not be removed — the sources are unaltered and the notice stays in the submodule.
- bzip2 1.0.8 (`core/third_party/bzip2`, a git submodule of `https://sourceware.org/git/bzip2.git`
  pinned to release tag `bzip2-1.0.8`, commit `6a8690fc8d26c815e798c588f796eabe9d684cf0`, M3.1
  part 2d) — statically linked into `fylz-archive` as libarchive's bzip2 filter backend, never a
  Cargo dependency. The tag has no CMake build, so `build.rs` compiles exactly the `Makefile`'s
  library objects (`blocksort.c huffman.c crctable.c randtable.c compress.c decompress.c bzlib.c`)
  with the `cc` crate; the `bzip2`/`bzip2recover` programs, tests and documentation are not built.
  **bzip2 licence** (SPDX `bzip2-1.0.6`; `core/third_party/bzip2/LICENSE`, copyright (C) 1996-2019
  Julian R Seward), BSD-style, with four conditions that this distribution meets and passes on:
  redistributions of source code retain the copyright notice, the list of conditions and the
  disclaimer (the unaltered `LICENSE` stays in the submodule); the origin of the software is not
  misrepresented; the sources are unaltered, and any altered version must be plainly marked as
  such; and the author's name is not used to endorse or promote products derived from it. Its
  `README` adds a PATENTS note: to the author's knowledge bzip2/libbzip2 uses no patented
  algorithms, but no patent search was carried out and no guarantee is given.
- `cmake`, `cc`, `shlex` and `find-msvc-tools` (`fylz-archive`'s build-time-only dependencies,
  M3.1; `cc` is now also a direct build-dependency of `fylz-archive`, for bzip2) are covered by
  `cargo deny` and never linked into the APK.

## Test-only dependency

- JUnit 4.13.2 — Eclipse Public License 1.0

## Release review procedure

Before each public release:

1. Run the `Release readiness` GitHub Actions workflow on the exact release commit.
2. Download and inspect `release-runtime-dependencies`.
3. Compare the generated graph with this notice and the prior release graph.
4. Review every new or upgraded family for licence compatibility, security advisories, provenance and maintenance status.
5. Review Google-distributed SDK terms when their artifacts or intended use change.
6. Update this notice when direct dependencies or material transitive obligations change.
7. Store the reviewed dependency graph with the release record.

No private Fonebrew Studio package, asset, model, credential, signing material or internal dependency may be included in the public Fylz build.
