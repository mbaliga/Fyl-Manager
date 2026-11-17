#!/usr/bin/env bash
# Builds the Rust core for iOS and packages it as an XCFramework plus uniffi's Swift bindings.
# macOS + Xcode only (needs the iOS SDKs and `xcodebuild -create-xcframework`); run from anywhere.
#
#   apple/scripts/build-xcframework.sh [--release]
#
# Outputs (both gitignored):
#   apple/Frameworks/FylzCoreFFI.xcframework   static libs: ios-arm64 and ios-arm64-simulator
#   apple/Generated/FylzCore.swift             the uniffi-generated Swift API
#
# Evidence: CI-LANE-WRITTEN. The Linux container has no Apple SDK, so no step below that touches
# an *-apple-ios target has ever run. The host-side half (uniffi-bindgen producing FylzCore.swift
# from a static library) was run on Linux and works.
set -euo pipefail

profile=debug; cargo_profile=()
if [[ "${1:-}" == "--release" ]]; then profile=release; cargo_profile=(--release); fi

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
core="$repo/core"
out="$repo/apple"
targets=(aarch64-apple-ios aarch64-apple-ios-sim)
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-16.0}"

command -v xcodebuild >/dev/null || { echo "xcodebuild not found: this script needs macOS with Xcode" >&2; exit 2; }
[[ -f "$core/third_party/libarchive/CMakeLists.txt" ]] || { echo "submodules missing: git submodule update --init --recursive" >&2; exit 2; }

rustup target add "${targets[@]}"

for t in "${targets[@]}"; do
  echo "== cargo build $t ($profile)"
  (cd "$core" && cargo build "${cargo_profile[@]}" -p fylz-ffi-apple --target "$t")
done

echo "== uniffi Swift bindings (host build of the generator, library mode on the sim static lib)"
gen="$out/Generated"; rm -rf "$gen"; mkdir -p "$gen"
(cd "$core" && cargo run -q -p fylz-ffi-apple --features uniffi-cli --bin uniffi-bindgen -- \
  generate --library "target/aarch64-apple-ios-sim/$profile/libfylz_ffi_apple.a" \
  --language swift --out-dir "$gen")

# The generated header + modulemap become the XCFramework's Headers (module FylzCoreFFI).
hdr="$(mktemp -d)"; cp "$gen/FylzCoreFFI.h" "$hdr/"; cp "$gen/FylzCoreFFI.modulemap" "$hdr/module.modulemap"
rm -f "$gen/FylzCoreFFI.h" "$gen/FylzCoreFFI.modulemap"

fw="$out/Frameworks/FylzCoreFFI.xcframework"; rm -rf "$fw"; mkdir -p "$out/Frameworks"
xcodebuild -create-xcframework \
  -library "$core/target/aarch64-apple-ios/$profile/libfylz_ffi_apple.a" -headers "$hdr" \
  -library "$core/target/aarch64-apple-ios-sim/$profile/libfylz_ffi_apple.a" -headers "$hdr" \
  -output "$fw"
echo "== done: $fw and $gen/FylzCore.swift"
