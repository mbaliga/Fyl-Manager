#!/usr/bin/env bash
# Static checks of the Linux desktop metadata (no build, no Qt). Needs desktop-file-utils and
# appstream (apt). Run from anywhere.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPID="io.github.mbaliga.Fylz"
desktop-file-validate "$HERE/$APPID.desktop"
# --no-net: never touch the network (the project's no-phoning-home rule applies to tooling too).
appstreamcli validate --no-net "$HERE/$APPID.metainfo.xml"
python3 -I - "$HERE/$APPID.svg" <<'PY'
import sys, xml.dom.minidom
xml.dom.minidom.parse(sys.argv[1])
PY
bash -n "$HERE/packaging/build-deb.sh" "$HERE/packaging/build-appimage.sh"
echo "linux metadata OK"
