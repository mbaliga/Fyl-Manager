//! Cross-compiles libarchive (`core/third_party/libarchive`, a git submodule pinned to v3.8.9 --
//! never a Cargo dependency, per `docs/agent/MASTER_PLAN.md` section 2.2: a permissively-licensed
//! C library vendored and built outside cargo, the same way an LGPL `.so` would be, kept out of
//! `deny.toml`'s dependency graph entirely since there is nothing for it to check) for whichever
//! target this crate itself is being built for, then links the resulting static library.
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
//! Every optional format/crypto backend (`ENABLE_ZLIB`, `ENABLE_ACL`, `ENABLE_OPENSSL`, ...) is
//! explicitly OFF for this first cut: libarchive's own CMake build silently drops a format that
//! needs a library it can't find rather than failing, so an implicit "whatever happens to be on
//! the build machine" set would make the crate's actual format coverage a function of the host
//! running the build -- unacceptable for a library meant to behave identically on every developer
//! machine and in CI. Compression backends (zlib, bzip2, xz, zstd, lz4 -- all permissive, per
//! section 2.2) are added back one at a time in a follow-up task, each verified to still build
//! before the next is added, never all at once.
use std::env;
use std::path::PathBuf;

fn main() {
    let target = env::var("TARGET").expect("cargo always sets TARGET");
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("cargo sets this"));
    let libarchive_src = manifest_dir.join("../../third_party/libarchive");
    println!(
        "cargo:rerun-if-changed={}",
        libarchive_src.join("CMakeLists.txt").display()
    );

    let mut cfg = cmake::Config::new(&libarchive_src);
    cfg.profile("Release")
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
        // Compression backends -- OFF here, added incrementally in a follow-up task (see this
        // file's own doc comment above).
        .define("ENABLE_ZLIB", "OFF")
        .define("ENABLE_BZip2", "OFF")
        .define("ENABLE_LZMA", "OFF")
        .define("ENABLE_ZSTD", "OFF")
        .define("ENABLE_LZ4", "OFF")
        .define("ENABLE_LZO", "OFF");

    if target.contains("android") {
        let toolchain = env::var("CARGO_NDK_CMAKE_TOOLCHAIN_PATH").expect(
            "fylz-archive must be built via `cargo ndk` for an Android target -- it sets \
             CARGO_NDK_CMAKE_TOOLCHAIN_PATH/ANDROID_ABI/ANDROID_PLATFORM, which a plain \
             `cargo build` for this target does not provide",
        );
        let android_abi = env::var("ANDROID_ABI").expect("cargo-ndk sets ANDROID_ABI");
        // cargo-ndk's own ANDROID_PLATFORM is the bare API level (e.g. "31"); the NDK's CMake
        // toolchain file wants it as "android-31".
        let api_level = env::var("ANDROID_PLATFORM").unwrap_or_else(|_| "26".to_string());
        cfg.define("CMAKE_TOOLCHAIN_FILE", toolchain)
            .define("ANDROID_ABI", android_abi)
            .define("ANDROID_PLATFORM", format!("android-{api_level}"));
    }

    let dst = cfg.build();
    println!(
        "cargo:rustc-link-search=native={}",
        dst.join("lib").display()
    );
    println!("cargo:rustc-link-lib=static=archive");
}
