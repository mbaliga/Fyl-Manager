//! Cross-compiles libarchive (`core/third_party/libarchive`, a git submodule pinned to v3.8.9 --
//! never a Cargo dependency, per `docs/agent/MASTER_PLAN.md` section 2.2: a permissively-licensed
//! C library vendored and built outside cargo, the same way an LGPL `.so` would be, kept out of
//! `deny.toml`'s dependency graph entirely since there is nothing for it to check), plus each
//! compression backend it is built against, for whichever target this crate itself is being built
//! for, then links the resulting static libraries.
//!
//! Two build shapes, told apart by Cargo's own `TARGET` env var:
//! - An Android target (`aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`)
//!   -- MUST be invoked via `cargo ndk`, which sets `CARGO_NDK_CMAKE_TOOLCHAIN_PATH`/`ANDROID_ABI`/
//!   `ANDROID_PLATFORM` for exactly this purpose (confirmed empirically: these are not documented
//!   in cargo-ndk's own README as a build-script contract, but a real `cargo ndk build` run always
//!   sets them before invoking `rustc`/build scripts). A plain `cargo build` for an Android target
//!   without going through `cargo ndk` fails loudly here rather than silently miscompiling.
//! - Any other target (the host, e.g. `x86_64-unknown-linux-gnu`) -- an ordinary native CMake
//!   build, no toolchain file. This path exists because `cargo test -p fylz-archive` and the
//!   workspace-wide `cargo test`/`clippy`/`deny check` CI gate (M2.6) all build for the host, and
//!   `app/build.gradle.kts`'s own `buildCoreDebug`/`buildCoreRelease` tasks additionally run a
//!   plain host `cargo build --lib -p fylz-ffi-android` for uniffi-bindgen's own introspection
//!   step -- once `fylz-archive` becomes a dependency of `fylz-ffi-android`, that step needs a
//!   working host build too.
//!
//! [build_companion] carries this same host-vs-`cargo ndk` routing for every compression backend
//! below libarchive itself, so it never has to be re-derived per library.
//!
//! Every optional format/crypto backend (`ENABLE_ZLIB`, `ENABLE_ACL`, `ENABLE_OPENSSL`, ...) is
//! explicitly OFF unless a companion for it has been built (below), so libarchive's format
//! coverage never becomes a function of "whatever happens to be on the build machine" --
//! libarchive's own CMake build silently drops a format that needs a library it can't find rather
//! than failing. Compression backends (zlib, bzip2, xz, zstd, lz4 -- all permissive, per section
//! 2.2) are added one at a time, each verified to still build before the next is added, never all
//! at once: lz4 first (M3.1 part 2a), the rest in follow-up tasks.
//!
//! Every companion, and libarchive itself, is built with `CMAKE_INSTALL_LIBDIR=lib` pinned
//! explicitly: `GNUInstallDirs` (which all of these projects use) picks `lib64` on Fedora/RHEL-
//! style 64-bit Linux hosts, so leaving it to the default would make the install layout -- and
//! therefore whether the cache variables preset below actually point at anything -- a function of
//! which distro happens to be running the build.
use std::env;
use std::path::Path;
use std::path::PathBuf;

/// Applies the host-vs-`cargo ndk` toolchain routing described in this file's own doc comment to
/// `cfg`. Shared by every CMake build this build script drives (libarchive and each companion),
/// so the contract -- which env vars `cargo ndk` must have set, and how its `ANDROID_PLATFORM`
/// maps onto the NDK toolchain file's own `android-<api>` spelling -- is expressed exactly once.
fn configure_android_toolchain(target: &str, library_name: &str, cfg: &mut cmake::Config) {
    if !target.contains("android") {
        return;
    }
    let toolchain = env::var("CARGO_NDK_CMAKE_TOOLCHAIN_PATH").unwrap_or_else(|_| {
        panic!(
            "fylz-archive's {library_name} build must be invoked via `cargo ndk` for an \
             Android target -- it sets CARGO_NDK_CMAKE_TOOLCHAIN_PATH/ANDROID_ABI/\
             ANDROID_PLATFORM, which a plain `cargo build` for this target does not provide"
        )
    });
    let android_abi = env::var("ANDROID_ABI")
        .unwrap_or_else(|_| panic!("cargo-ndk sets ANDROID_ABI (building {library_name})"));
    // cargo-ndk's own ANDROID_PLATFORM is the bare API level (e.g. "31"); the NDK's CMake
    // toolchain file wants it as "android-31".
    let api_level = env::var("ANDROID_PLATFORM").unwrap_or_else(|_| "26".to_string());
    cfg.define("CMAKE_TOOLCHAIN_FILE", toolchain)
        .define("ANDROID_ABI", android_abi)
        .define("ANDROID_PLATFORM", format!("android-{api_level}"));
}

/// Builds one compression-backend companion (a self-contained CMake project vendored under
/// `core/third_party/<name>`) as a static library, the same way for every one of them: its own
/// install prefix under `$OUT_DIR/<name>` (kept separate from libarchive's own `$OUT_DIR`, and
/// from every other companion's, so each has an independent CMake build directory rather than
/// three projects sharing and repeatedly reconfiguring one), `Release` profile,
/// `CMAKE_INSTALL_LIBDIR=lib` pinned (see this file's own doc comment), `options` applied
/// verbatim, then the same host/`cargo ndk` toolchain routing libarchive itself uses. Returns the
/// install prefix, so the caller can preset libarchive's own `FIND_PATH`/`FIND_LIBRARY` cache
/// variables from it and emit this companion's own link lines.
fn build_companion(target: &str, name: &str, src: &Path, options: &[(&str, &str)]) -> PathBuf {
    println!(
        "cargo:rerun-if-changed={}",
        src.join("CMakeLists.txt").display()
    );
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join(name);
    let mut cfg = cmake::Config::new(src);
    cfg.profile("Release")
        .out_dir(&out_dir)
        .define("CMAKE_INSTALL_LIBDIR", "lib");
    for (key, value) in options {
        cfg.define(*key, *value);
    }
    configure_android_toolchain(target, name, &mut cfg);
    cfg.build()
}

/// The path a companion built by [build_companion] installs its static library to, given its
/// output name (`lz4` -> `liblz4.a`) -- every library here (and libarchive) is Unix-only
/// (Android/Linux), so there is no `.lib`/DLL-import-library naming to account for.
fn static_lib_path(prefix: &Path, output_name: &str) -> PathBuf {
    prefix.join("lib").join(format!("lib{output_name}.a"))
}

fn main() {
    let target = env::var("TARGET").expect("cargo always sets TARGET");
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("cargo sets this"));
    let third_party = manifest_dir.join("../../third_party");

    let lz4_prefix = build_companion(
        &target,
        "lz4",
        &third_party.join("lz4/build/cmake"),
        &[
            ("BUILD_SHARED_LIBS", "OFF"),
            ("BUILD_STATIC_LIBS", "ON"),
            ("LZ4_BUILD_CLI", "OFF"),
        ],
    );

    let libarchive_src = third_party.join("libarchive");
    println!(
        "cargo:rerun-if-changed={}",
        libarchive_src.join("CMakeLists.txt").display()
    );

    let mut cfg = cmake::Config::new(&libarchive_src);
    cfg.profile("Release")
        .define("CMAKE_INSTALL_LIBDIR", "lib")
        .define("BUILD_SHARED_LIBS", "OFF")
        // Command-line tools this crate never calls (bsdtar/bsdcpio/bsdcat/bsdunzip) and their
        // own test/coverage machinery.
        .define("ENABLE_TAR", "OFF")
        .define("ENABLE_CPIO", "OFF")
        .define("ENABLE_CAT", "OFF")
        .define("ENABLE_UNZIP", "OFF")
        .define("ENABLE_TEST", "OFF")
        .define("ENABLE_COVERAGE", "OFF")
        // POSIX ACL/xattr preservation and iconv-based filename transcoding: not attempted in
        // this first cut (M3.7 handles filename encoding at a higher level for legacy ZIPs
        // specifically; general xattr/ACL preservation is out of M3's own stated scope).
        .define("ENABLE_ACL", "OFF")
        .define("ENABLE_XATTR", "OFF")
        .define("ENABLE_ICONV", "OFF")
        // Crypto/hash backends: libarchive's own built-in digest implementations (used for
        // mtree/xar checksums, never for AES) cover what this crate needs without an external
        // library; AES for encrypted ZIP/7z entries is handled by libarchive's own bundled
        // implementation, not one of these.
        .define("ENABLE_OPENSSL", "OFF")
        .define("ENABLE_MBEDTLS", "OFF")
        .define("ENABLE_NETTLE", "OFF")
        .define("ENABLE_LIBB2", "OFF")
        // XML-based formats (xar's header, some mtree variants): not in M3's format table.
        .define("ENABLE_LIBXML2", "OFF")
        .define("ENABLE_EXPAT", "OFF")
        .define("ENABLE_PCREPOSIX", "OFF")
        .define("ENABLE_PCRE2POSIX", "OFF")
        // Compression backends not yet added -- OFF, added incrementally in follow-up tasks (see
        // this file's own doc comment above).
        .define("ENABLE_ZLIB", "OFF")
        .define("ENABLE_BZip2", "OFF")
        .define("ENABLE_LZMA", "OFF")
        .define("ENABLE_ZSTD", "OFF")
        .define("ENABLE_LZO", "OFF")
        // lz4: preset libarchive's own FIND_PATH/FIND_LIBRARY cache variables to the companion
        // just built, so its CMake never searches for lz4 itself. Presetting the cache variables
        // (rather than CMAKE_PREFIX_PATH/LZ4_ROOT) is required under the NDK toolchain, whose
        // CMAKE_FIND_ROOT_PATH_MODE_* ONLY setting re-roots any prefix search under the NDK
        // sysroot (docs/agent/DESIGN-M31-COMPRESSION-LIBS.md section 1).
        .define("ENABLE_LZ4", "ON")
        .define("LZ4_INCLUDE_DIR", lz4_prefix.join("include"))
        .define("LZ4_LIBRARY", static_lib_path(&lz4_prefix, "lz4"));

    configure_android_toolchain(&target, "libarchive", &mut cfg);

    let dst = cfg.build();
    println!(
        "cargo:rustc-link-search=native={}",
        dst.join("lib").display()
    );
    println!("cargo:rustc-link-lib=static=archive");

    // `libarchive.a` does not bundle its dependencies (its own CMakeLists only records an
    // interface link), so every companion's link line is emitted here, after `static=archive`.
    println!(
        "cargo:rustc-link-search=native={}",
        lz4_prefix.join("lib").display()
    );
    println!("cargo:rustc-link-lib=static=lz4");
}
