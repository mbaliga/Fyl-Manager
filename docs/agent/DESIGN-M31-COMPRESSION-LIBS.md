# M3.1 part 2 — vendored compression libraries for libarchive: verified fact sheet

Research date 2026-09-25. Every tag, commit and licence below was read from a shallow clone of the
upstream repository *at that tag* (clones in this session's scratchpad `src/`), cross-checked against
`git ls-remote --tags` of the upstream remote and the project's own release page. Local facts come from
`core/crates/fylz-archive/build.rs`, `core/third_party/libarchive/CMakeLists.txt` (v3.8.9), CMake 3.28.3's
Find modules, the `cmake` crate 0.1.58 source in `~/.cargo/registry`, and NDK 28.2.13676358 at
`/opt/android-sdk/ndk`. Nothing here is guessed; the two items I could not confirm are marked UNVERIFIED.

## 1. How libarchive 3.8.9 finds them (`CMakeLists.txt` lines 489–726)

| Option (exact case) | Discovery | Cache variables to preset from `build.rs` | Result used |
|---|---|---|---|
| `ENABLE_ZLIB` | `FIND_PACKAGE(ZLIB 1.2.1)` → CMake's FindZLIB | `ZLIB_INCLUDE_DIR`, `ZLIB_LIBRARY` (FindZLIB: "Allow ZLIB_LIBRARY to be set manually"); `ZLIB_ROOT` is only a search hint | `ZLIB_LIBRARIES`; sets `HAVE_LIBZ`, `HAVE_ZLIB_H` |
| `ENABLE_BZip2` | `FIND_PACKAGE(BZip2)` → FindBZip2 | `BZIP2_INCLUDE_DIR`, `BZIP2_LIBRARIES` (module skips `find_library` when `BZIP2_LIBRARIES` is set); then `CHECK_SYMBOL_EXISTS(BZ2_bzCompressInit)` link test | `BZIP2_LIBRARIES`; `HAVE_LIBBZ2`, `HAVE_BZLIB_H` |
| `ENABLE_LZMA` | `FIND_PACKAGE(LibLZMA)` → FindLibLZMA | `LIBLZMA_INCLUDE_DIR`, `LIBLZMA_LIBRARY`; module then requires `check_library_exists` for `lzma_auto_decoder`, `lzma_easy_encoder`, `lzma_lzma_preset` (links the .a) | `LIBLZMA_LIBRARIES`; `HAVE_LIBLZMA`, `HAVE_LZMA_H`, `HAVE_LZMA_STREAM_ENCODER_MT` |
| `ENABLE_ZSTD` | pkg-config hint (UNIX) + `FIND_PATH(ZSTD_INCLUDE_DIR zstd.h)` + `FIND_LIBRARY(ZSTD_LIBRARY NAMES zstd libzstd zstd_static)` | `ZSTD_INCLUDE_DIR`, `ZSTD_LIBRARY`; then `CHECK_FUNCTION_EXISTS(ZSTD_decompressStream)` → **`HAVE_LIBZSTD` only if that link test passes** | `ZSTD_LIBRARY`; `HAVE_ZSTD_H`, `HAVE_ZSTD_compressStream`, `HAVE_ZSTD_minCLevel` |
| `ENABLE_LZ4` | `FIND_PATH(LZ4_INCLUDE_DIR lz4.h)` + `FIND_LIBRARY(LZ4_LIBRARY NAMES lz4 liblz4)` | `LZ4_INCLUDE_DIR`, `LZ4_LIBRARY`; checks `lz4hc.h` → `HAVE_LZ4HC_H` | `LZ4_LIBRARY`; `HAVE_LIBLZ4`, `HAVE_LZ4_H` |

Facts that shape `build.rs`:

- **Presetting the cache variables is the right mechanism.** CMake's `find_path`/`find_library` are no-ops
  when the result variable is already set, so `cfg.define("ZLIB_LIBRARY", "<abs>/libz.a")` bypasses search
  entirely. libarchive supports this directly (it `MARK_AS_ADVANCED(CLEAR …)`s exactly these variables); no
  `CMAKE_PREFIX_PATH` needed.
- **`CMAKE_PREFIX_PATH`/`*_ROOT` do *not* work under the NDK toolchain.** NDK 28's
  `android-legacy.toolchain.cmake` (included by default from `android.toolchain.cmake`) does
  `list(APPEND CMAKE_FIND_ROOT_PATH "${ANDROID_NDK}")` and sets `CMAKE_FIND_ROOT_PATH_MODE_{LIBRARY,INCLUDE,PACKAGE} ONLY`
  (lines 292–321), so every prefix is re-rooted under the NDK/sysroot; its own comment says users of
  `CMAKE_PREFIX_PATH` "won't be able to find" separately built packages (android/ndk#2048) and mentions
  "prevent the NDK's libz from being picked up" (android-ndk/ndk#517). Explicit cache variables sidestep this.
- **The `ZLIB_WINAPI`/`USE_BZIP2_*`/`LZMA_API_STATIC` probes are Windows-only.** libarchive's
  `TRY_MACRO_FOR_LIBRARY` (lines 417–449) is wrapped in `IF(WIN32 AND NOT CYGWIN)`; xz's `lzma.h` only uses
  `LZMA_API_STATIC` for `_WIN32 && !__GNUC__`. No defines are needed or added on Android/Linux.
- **`libarchive.a` does not contain its dependencies.** `libarchive/CMakeLists.txt:266`
  `TARGET_LINK_LIBRARIES(archive_static ${ADDITIONAL_LIBS})` only records an interface, so `build.rs` must
  emit `cargo:rustc-link-search` for each companion prefix and `cargo:rustc-link-lib=static=z|bz2|lzma|zstd|lz4`
  *after* `static=archive` (the cc crate emits bz2's automatically).
- **Install layout.** libarchive, zlib, xz, zstd and lz4 all use `GNUInstallDirs`: `lib` on Android
  (`CMAKE_SYSTEM_NAME=Android`) and Debian/Ubuntu hosts, but **`lib64` on Fedora/RHEL-style 64-bit Linux
  hosts**. Today's `dst.join("lib")` only works because this machine is Ubuntu 24.04. Pass
  `-DCMAKE_INSTALL_LIBDIR=lib` to libarchive *and* every companion. Headers all land in `include/`
  (xz: `include/lzma.h` + `include/lzma/*.h`).
- **cmake crate behaviour to mirror for each companion.** `cmake` 0.1.58 detects the NDK when
  `ANDROID_ABI` is defined and `CMAKE_TOOLCHAIN_FILE`'s basename is `android.toolchain.cmake`
  (`uses_android_ndk`, lib.rs:414) and then disables cc's default flags and compiler injection, letting the
  toolchain file drive; on the host it injects cc's flags (including `-fPIC`) into `CMAKE_C_FLAGS`. The NDK
  toolchain sets `CMAKE_POSITION_INDEPENDENT_CODE TRUE`. So the current libarchive toolchain block, factored
  into a helper, is exactly what each companion needs.
- ZIP deflate reading is `#ifdef HAVE_ZLIB_H` in `archive_read_support_format_zip.c`; without zlib the
  crate cannot read deflated ZIP entries — zlib is the highest-value library even if not the least risky.

## 2. Per-library sheets

### 2.1 zlib

| Item | Verified value |
|---|---|
| Submodule URL / tag | `https://github.com/madler/zlib.git`, tag **`v1.3.2`** (commit `da607da739fa6047df13e66a2af6b8bec7c2a498`), released 2026-02-17; latest per <https://github.com/madler/zlib/releases/latest> and <https://zlib.net/> |
| Licence at tag | **zlib licence**, `LICENSE`: "(C) 1995-2026 Jean-loup Gailly and Mark Adler" — <https://github.com/madler/zlib/blob/v1.3.2/LICENSE>. §2.2: allowed anywhere (listed by name). `contrib/minizip` carries `LICENSE.Info-Zip` but is not built (below) |
| CMake | Root `CMakeLists.txt`, `cmake_minimum_required(3.12...3.31)`. The build was rewritten in 1.3.1.2/1.3.2 ("Complete rewrite of CMake build [Vollstrecker]", ChangeLog) so 1.3.1-era advice is stale. Options: `ZLIB_BUILD_SHARED=OFF ZLIB_BUILD_STATIC=ON ZLIB_BUILD_TESTING=OFF ZLIB_INSTALL=ON` (+`CMAKE_INSTALL_LIBDIR=lib`). `contrib/` options `ZLIB_BUILD_<name>` default OFF, `MINIZIP_ENABLE_BZIP2` OFF, so minizip/bzip2 never trigger |
| Output | target `zlibstatic`, `OUTPUT_NAME z` (the `s` suffix is WIN32-only) → `lib/libz.a`; `include/zlib.h` + generated `include/zconf.h`; also `lib/cmake/zlib`, `lib/pkgconfig`, man/doc files (harmless). `zconf.h` is now generated into the *binary* dir (no in-source rename, submodule stays clean) |
| Android gotchas | (1) **The NDK sysroot ships zlib**: `usr/include/zlib.h` (`ZLIB_VERSION "1.3.0.1-motley"`), `usr/lib/<triple>/libz.a` and `<triple>/<api>/libz.so` for every ABI. With `ENABLE_ZLIB=ON` and no explicit `ZLIB_INCLUDE_DIR`/`ZLIB_LIBRARY`, FindZLIB under the NDK finds *that* zlib silently. (2) Final link name clash on `-lz`: rustc's `-L` for our prefix precedes the sysroot so `static=z` should resolve to ours, but verify (`cargo ndk … -vv` link line / `llvm-readelf -d` shows no `NEEDED libz.so`). UNVERIFIED until built. (3) Decision point: linking the NDK's `libz.so` is a documented stable NDK API and needs no build, but contradicts build.rs's "identical on every machine" rationale — record whichever is chosen |

### 2.2 bzip2

| Item | Verified value |
|---|---|
| Submodule URL / tag | `https://sourceware.org/git/bzip2.git` (official; the gitweb UI is behind Anubis anti-bot but smart-HTTP clone/ls-remote works), tag **`bzip2-1.0.8`** (annotated `75a94bea…`, commit `6a8690fc8d26c815e798c588f796eabe9d684cf0`). `https://gitlab.com/bzip2/bzip2.git` carries the identical tag/commit. 1.0.8 (13 Jul 2019) is still the newest tag on both remotes |
| Licence at tag | **bzip2 licence** (SPDX `bzip2-1.0.6`), `LICENSE`: "copyright (C) 1996-2019 Julian R Seward", 4 conditions (retain notice; no misrepresentation of origin; mark altered versions; no endorsement). BSD-style permissive → allowed anywhere under §2.2's "BSD" row; record the SPDX id since it is not verbatim BSD. <https://gitlab.com/bzip2/bzip2/-/blob/bzip2-1.0.8/LICENSE> (same blob as sourceware). README has a PATENTS disclaimer ("does not use any patented algorithms… cannot give any guarantee") |
| CMake | **None at the tag** (only `Makefile`, `Makefile-libbz2_so`, `makefile.msc`). Correction to the task premise: sourceware **master** (`f5dfc96`, 2026-07-11) *also* has no CMakeLists.txt; the `CMakeLists.txt`/`meson.build` live only in GitLab bzip2/bzip2 master (1.1.0-dev, last commit 2023-05-31, different `COPYING`) — unreleased, do not pin |
| Alternative | `cc` crate (already in the build graph via `cmake`; add `cc = "1"` directly) compiling the `Makefile` `OBJS` list: `blocksort.c huffman.c crctable.c randtable.c compress.c decompress.c bzlib.c` (each includes only `bzlib_private.h` → `bzlib.h`), with `-D_FILE_OFFSET_BITS=64` (Makefile `BIGFILES`). Do **not** define `BZ_NO_STDIO`: it switches `AssertH` to an application-supplied `bz_internal_error()`. `.compile("bz2")` → `OUT_DIR/libbz2.a`; point `BZIP2_INCLUDE_DIR` at the submodule root (where `bzlib.h` lives) and `BZIP2_LIBRARIES` at that `.a`. cc emits the link metadata itself |
| Android gotchas | None specific; cargo-ndk exports `CC_<target>`/`AR_<target>` so cc picks NDK clang. `_FILE_OFFSET_BITS=64` is honoured by bionic on 32-bit only from API 24 (we target 26). FindBZip2's `CHECK_SYMBOL_EXISTS` link test must succeed against our `.a` — trivially does |
| Notices | Preserve `LICENSE` verbatim (condition 1) in `THIRD_PARTY_NOTICES.md`; `bzlib.h` header points to it |

### 2.3 xz / liblzma

| Item | Verified value |
|---|---|
| Submodule URL / tag | `https://github.com/tukaani-project/xz.git` (canonical also `https://git.tukaani.org/xz.git`, both listed on <https://tukaani.org/xz/>), tag **`v5.8.4`** (commit `d3e650e63c110e830fd5391e7f8b45df0b91d3da`), released 2026-09-09, "latest stable" on tukaani.org and <https://github.com/tukaani-project/xz/releases/latest>; `version.h` = 5.8.4 STABLE |
| Licence at tag | `COPYING` (<https://github.com/tukaani-project/xz/blob/v5.8.4/COPYING>): **liblzma is 0BSD**; tools 0BSD except GNU `getopt_long` from `lib/` (LGPLv2.1+, only on systems lacking it); `xzgrep`/`xzdiff`/`xzless`/`xzmore` scripts GPLv2+; autotools files GPLv2+/GPLv3+; `extra/` mixed. **What we compile**: `src/liblzma/**`, `src/common/*` (tuklib), root `CMakeLists.txt`, `cmake/*.cmake` — all 189 SPDX-tagged files under `src/liblzma`+`src/common` are `0BSD` (grep of the clone); the only untagged files are `src/common/w32_application.manifest*` (Windows-only, not compiled). `lib/getopt*.c` (LGPL) is never part of liblzma and bionic has `getopt_long` anyway. → compliant with §2.2 (0BSD ≈ ISC). Sufficient notice per COPYING: "Copyright (C) The XZ Utils authors and contributors" |
| CMake | Root `CMakeLists.txt`, `cmake_minimum_required(3.20...4.2)` (we have 3.28). Options *at this tag* (renamed to `XZ_*` since 5.6.x): `BUILD_SHARED_LIBS=OFF` (default OFF = static) `XZ_TOOL_XZ=OFF XZ_TOOL_XZDEC=OFF XZ_TOOL_LZMADEC=OFF XZ_TOOL_LZMAINFO=OFF XZ_TOOL_SCRIPTS=OFF XZ_TOOL_SYMLINKS=OFF XZ_DOC=OFF XZ_NLS=OFF XZ_DOXYGEN=OFF BUILD_TESTING=OFF` (tests via `include(CTest)` in `tests/tests.cmake`) + `CMAKE_INSTALL_LIBDIR=lib`. Leave `XZ_THREADS=yes` (posix on Android; gives `lzma_stream_encoder_mt`), `XZ_ARM64_CRC32=ON` |
| Output | target `liblzma`, `OUTPUT_NAME lzma` → `lib/liblzma.a`; `include/lzma.h` + `include/lzma/*.h` (FindLibLZMA reads `lzma/version.h`); `lib/cmake/liblzma`, `lib/pkgconfig`. `LZMA_API_STATIC` is added as an INTERFACE define only on WIN32/CYGWIN |
| Android gotchas | `XZ_NLS` auto-detect runs `find_package(Intl)`/`Gettext` — under ONLY-mode they are not found, but set OFF explicitly. ARM64 CRC32 uses `check_symbol_exists(getauxval sys/auxv.h)` + `HWCAP_CRC32` (bionic has both since API 18; confirm `HAVE_GETAUXVAL` in the configure log). FindLibLZMA's `check_library_exists` links `liblzma.a` alone — fine on bionic (pthread in libc) and glibc ≥ 2.34 hosts. No `__ANDROID__` special-casing exists in xz (grep), so none is expected to be needed. Largest option surface of the five |

### 2.4 zstd

| Item | Verified value |
|---|---|
| Submodule URL / tag | `https://github.com/facebook/zstd.git`, tag **`v1.5.7`** (commit `f8745da6ff1ad1e7bab384bd1f9d742439278e99`), 2025-02-19, latest per <https://github.com/facebook/zstd/releases/latest>. Newer-sorting tags `v1.5.7-kernel`/`v1.5.5-kernel` are Linux-kernel exports — **do not pin** |
| Licence at tag | **Dual BSD-3-Clause OR GPLv2**: `LICENSE` = "BSD License For Zstandard software, Copyright (c) Meta Platforms, Inc. and affiliates" (3-clause); `COPYING` = GPLv2; README §License "dual-licensed under BSD OR GPLv2"; every `lib/` source header (e.g. `lib/common/zstd_common.c`) says "You may select, at your option, one of the above-listed licenses". `build/cmake/CMakeLists.txt` carries the same dual header. → **select BSD-3-Clause** and record the election in `THIRD_PARTY_NOTICES.md`. <https://github.com/facebook/zstd/blob/v1.5.7/LICENSE> |
| CMake | `build/cmake/CMakeLists.txt`, `cmake_minimum_required(3.10)`; `lib/` sub-project is `project(libzstd C ASM)`. Options: `ZSTD_BUILD_PROGRAMS=OFF ZSTD_BUILD_SHARED=OFF ZSTD_BUILD_STATIC=ON ZSTD_BUILD_TESTS=OFF ZSTD_BUILD_CONTRIB=OFF` (+`CMAKE_INSTALL_LIBDIR=lib`); optional size trims `ZSTD_LEGACY_SUPPORT=OFF` (v0.x frames; libarchive doesn't need them), `ZSTD_BUILD_DICTBUILDER=OFF`. **Set `ZSTD_MULTITHREAD_SUPPORT` explicitly**: its default is OFF `if(ANDROID)` and ON elsewhere, so an unset value makes host and Android libs differ |
| Output | target `libzstd_static`, `OUTPUT_NAME zstd` (non-MSVC) → `lib/libzstd.a` (PIC forced by `POSITION_INDEPENDENT_CODE On`); `include/zstd.h zdict.h zstd_errors.h` (GLOB `lib/*.h`); `lib/cmake/zstd`, `lib/pkgconfig` |
| Android gotchas | Assembly `lib/decompress/huf_decompress_amd64.S` is compiled only when `CMAKE_SYSTEM_PROCESSOR` matches x86_64 *and* the `-z noexecstack` probe passes; every other case gets `-DZSTD_DISABLE_ASM` — **arm64 has no assembly**. `if(ANDROID)` reads `${ANDROID_PLATFORM_LEVEL}` unbraced (legacy toolchain sets it from `ANDROID_PLATFORM`; API < 24 adds `LIBC_NO_FSEEKO`, we are at 26). `zstd_deps.h` already avoids `_GNU_SOURCE` on `__ANDROID__` (no `qsort_r`). libarchive's `CHECK_FUNCTION_EXISTS(ZSTD_decompressStream)` links the `.a`: with multithreading ON it needs pthread symbols — in bionic libc, fine |

### 2.5 lz4

| Item | Verified value |
|---|---|
| Submodule URL / tag | `https://github.com/lz4/lz4.git`, tag **`v1.10.0`** (commit `ebb370ca83af193212df4dcbadcc5d87bc0de2f0`), 2024-07-22, latest per <https://github.com/lz4/lz4/releases/latest> and ls-remote |
| Licence at tag | Root `LICENSE`: "all files in the `lib` directory use a BSD 2-Clause license — all other files use a GPL-2.0-or-later license, unless explicitly stated otherwise". `lib/LICENSE` = **BSD 2-Clause**, "Copyright (c) 2011-2020, Yann Collet"; `lib/lz4.c` and `lib/xxhash.c` headers say BSD 2-Clause; `programs/COPYING` = GPL-2.0-or-later (not built); `build/cmake/CMakeLists.txt` is CC0 (public-domain dedication; a build script, never linked). Everything we compile (`lib/*.c`: `lz4.c lz4hc.c lz4frame.c lz4file.c xxhash.c`) is BSD-2-Clause. <https://github.com/lz4/lz4/blob/v1.10.0/lib/LICENSE>, <https://github.com/lz4/lz4/blob/v1.10.0/LICENSE> |
| CMake | `build/cmake/CMakeLists.txt`, `cmake_minimum_required(3.5)`. Options: `BUILD_SHARED_LIBS=OFF BUILD_STATIC_LIBS=ON LZ4_BUILD_CLI=OFF` (+`CMAKE_INSTALL_LIBDIR=lib`); `LZ4_POSITION_INDEPENDENT_LIB` defaults ON. No `LZ4_BUILD_LEGACY_LZ4C` and no tests at this tag. `LZ4_BUNDLED_MODE` auto-ON only when `add_subdirectory`'d (disables install); the cmake crate runs it top-level, so install works |
| Output | target `lz4_static`, `OUTPUT_NAME lz4` → `lib/liblz4.a`; `include/lz4.h lz4hc.h lz4frame.h lz4file.h` (`lz4hc.h` present → `HAVE_LZ4HC_H`); `lib/cmake/lz4`, `lib/pkgconfig` |
| Android gotchas | None found (no Android mentions in `lib/` or the CMake file) |

## 3. §2.2 verdict

None of the five violates §2.2 when statically linked: zlib (zlib), bzip2 (bzip2-1.0.6, BSD-style), liblzma
(0BSD — the GPL/LGPL parts of the xz tree are never compiled into liblzma), zstd (elect BSD-3-Clause of the
dual licence), lz4 `lib/` (BSD-2-Clause; GPL `programs/` never built). Record each licence, the zstd election,
and the "0BSD, XZ Utils authors" notice in `THIRD_PARTY_NOTICES.md`; add each `LICENSE` path to the
`core/third_party/README.md` inventory.

## 4. Proposed order (least → most risky)

1. **lz4** — one CMake file, no configure probes, no threads, no assembly; establishes the pattern
   (helper `build_companion()` reusing the current toolchain block; `CMAKE_INSTALL_LIBDIR=lib`; preset
   `*_INCLUDE_DIR`/`*_LIBRARY`; emit link lines).
2. **zstd** — same shape; assembly only on x86_64; pin `ZSTD_MULTITHREAD_SUPPORT` explicitly.
3. **zlib** — highest value (ZIP deflate, gzip) but carries the only shadowing hazard: assert in `build.rs`
   that libarchive's `CMakeCache.txt` `ZLIB_LIBRARY` equals our path and check the final `.so` has no
   `NEEDED libz.so`.
4. **bzip2** — trivial code, but a second build mechanism (`cc`) and a link-test-based Find module.
5. **xz** — CMake ≥ 3.20, the most configure probes under the NDK, threads, runtime CRC32 detection, and
   option names that changed across 5.6→5.8 (pin-dependent).

If value-first is preferred, move zlib to the front; the risk ordering above is what was asked for.
