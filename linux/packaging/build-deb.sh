#!/usr/bin/env bash
# Build an UNSIGNED .deb of the Fylz desktop shell from an already-built `fylz` binary.
#   linux/packaging/build-deb.sh <path-to-fylz-binary> [out-dir]
# Depends: is derived from the binary's real shared-library needs (ldd + dpkg -S), so it is
# correct for whichever Ubuntu/Debian release the binary was built on -- and ONLY installable on
# that release or newer. dpkg-deb only; no root needed (--root-owner-group).
set -euo pipefail

BIN="${1:?usage: build-deb.sh <fylz-binary> [out-dir]}"
OUT="${2:-$PWD}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
APPID="io.github.mbaliga.Fylz"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/core/crates/fylz-ffi-qt/Cargo.toml" | head -n1)"
ARCH="$(dpkg --print-architecture)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

install -Dm755 "$BIN" "$STAGE/usr/bin/fylz"
install -Dm644 "$HERE/$APPID.desktop" "$STAGE/usr/share/applications/$APPID.desktop"
install -Dm644 "$HERE/$APPID.svg" "$STAGE/usr/share/icons/hicolor/scalable/apps/$APPID.svg"
install -Dm644 "$HERE/$APPID.metainfo.xml" "$STAGE/usr/share/metainfo/$APPID.metainfo.xml"
install -Dm644 "$ROOT/LICENSE" "$STAGE/usr/share/doc/fylz/copyright"
# Strip only on the staged copy (the cargo artifact stays debuggable).
strip --strip-unneeded "$STAGE/usr/bin/fylz" 2>/dev/null || true

# Which Qt did cxx-qt-build link? (It prefers qmake6 when both are installed; pin it with
# QT_VERSION_MAJOR=6 QMAKE=/usr/lib/qt6/bin/qmake when building.) The QML modules the shell imports
# at run time are not visible to ldd, so name them per Qt major.
if ldd "$STAGE/usr/bin/fylz" | grep -q 'libQt6'; then
    qml_deps="qml6-module-qtquick, qml6-module-qtquick-window"
elif ldd "$STAGE/usr/bin/fylz" | grep -q 'libQt5'; then
    qml_deps="qml-module-qtquick2, qml-module-qtquick-window2"
else
    echo "build-deb: $BIN links neither Qt 5 nor Qt 6" >&2; exit 1
fi

# Runtime deps: the packages that own each linked library, minus the libc family we always need
# anyway; plus the QML module the shell imports at run time (not visible to ldd).
deps="$(ldd "$STAGE/usr/bin/fylz" | awk '/=>/ && $3 ~ /^\// {print $3}' | while read -r lib; do
    dpkg -S "$(readlink -f "$lib")" 2>/dev/null | cut -d: -f1 | head -n1
done | sort -u | paste -sd, | sed 's/,/, /g')"
deps="$deps, $qml_deps"
deps="$(tr ',' '\n' <<<"$deps" | sed 's/^ *//' | grep -v '^$' | sort -u | paste -sd, | sed 's/,/, /g')"

mkdir -p "$STAGE/DEBIAN"
SIZE="$(du -sk "$STAGE/usr" | cut -f1)"
cat > "$STAGE/DEBIAN/control" <<EOF
Package: fylz
Version: $VERSION
Architecture: $ARCH
Maintainer: Fylz maintainers <mbaliga@users.noreply.github.com>
Installed-Size: $SIZE
Depends: $deps
Section: utils
Priority: optional
Homepage: https://github.com/mbaliga/Fyl-Manager
Description: Fylz file manager (early Qt shell)
 Browse folders and look inside archives without extracting them. Read-only
 in this version. No telemetry; it makes no network connections.
EOF
mkdir -p "$OUT"
DEB="$OUT/fylz_${VERSION}_${ARCH}.deb"
dpkg-deb --root-owner-group -Zxz --build "$STAGE" "$DEB" >/dev/null
echo "$DEB"
