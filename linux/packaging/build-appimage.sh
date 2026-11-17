#!/usr/bin/env bash
# Build an UNSIGNED AppImage of the Fylz desktop shell (CI only; needs the Qt 5 dev packages and
# network access to GitHub releases). Qt is bundled DYNAMICALLY (LGPL: replaceable libraries).
#   linux/packaging/build-appimage.sh <path-to-fylz-binary> [out-dir]
# Tool versions are pinned and sha256-verified below. The resulting AppImage embeds the glibc of
# the build host, so it runs on that Ubuntu release and newer, not on older distros.
set -euo pipefail

BIN="${1:?usage: build-appimage.sh <fylz-binary> [out-dir]}"
OUT="${2:-$PWD}"
# The script cd's into a temp dir before linuxdeploy runs, so a relative binary path must be
# resolved now, while we are still in the caller's directory.
[ -f "$BIN" ] || { echo "no such binary: $BIN" >&2; exit 1; }
BIN="$(cd "$(dirname "$BIN")" && pwd)/$(basename "$BIN")"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPID="io.github.mbaliga.Fylz"
case "$(uname -m)" in
    x86_64)
        A=x86_64
        LD_SHA=4648f278ab3ef31f819e67c30d50f462640e5365a77637d7e6f2ad9fd0b4522a
        QT_SHA=15106be885c1c48a021198e7e1e9a48ce9d02a86dd0a1848f00bdbf3c1c92724 ;;
    aarch64)
        A=aarch64
        LD_SHA=06706ac8189797dccd36bd384105892cb5e6e71f784f4df526cc958adc223cd6
        QT_SHA=bf1c24aff6d749b5cf423afad6f15abd4440f81dec1aab95706b25f6667cdcf1 ;;
    *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
TOOLS="$(mktemp -d)"; APPDIR="$(mktemp -d)/AppDir"
trap 'rm -rf "$TOOLS" "$(dirname "$APPDIR")"' EXIT

fetch() { # url sha256 dest
    curl -fsSL --retry 3 -o "$3" "$1"
    echo "$2  $3" | sha256sum -c -
    chmod +x "$3"
}
fetch "https://github.com/linuxdeploy/linuxdeploy/releases/download/1-alpha-20250213-2/linuxdeploy-$A.AppImage" "$LD_SHA" "$TOOLS/linuxdeploy"
fetch "https://github.com/linuxdeploy/linuxdeploy-plugin-qt/releases/download/1-alpha-20250213-1/linuxdeploy-plugin-qt-$A.AppImage" "$QT_SHA" "$TOOLS/linuxdeploy-plugin-qt-$A.AppImage"
ln -s "linuxdeploy-plugin-qt-$A.AppImage" "$TOOLS/linuxdeploy-plugin-qt"

# AppImages cannot FUSE-mount on a stock CI runner: extract-and-run instead.
export APPIMAGE_EXTRACT_AND_RUN=1 PATH="$TOOLS:$PATH" ARCH="$A"
export QML_SOURCES_PATHS="$HERE/../core/crates/fylz-ffi-qt/qml"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$HERE/../core/crates/fylz-ffi-qt/Cargo.toml" | head -n1)"
export LINUXDEPLOY_OUTPUT_VERSION="$VERSION"

mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
cd "$(dirname "$APPDIR")"
linuxdeploy --appdir "$APPDIR" --plugin qt \
    --executable "$BIN" \
    --desktop-file "$HERE/$APPID.desktop" \
    --icon-file "$HERE/$APPID.svg" \
    --output appimage
mv ./*.AppImage "$OUT/"
ls -1 "$OUT"/*.AppImage
