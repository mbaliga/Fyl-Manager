#!/usr/bin/env bash
# Offscreen smoke test of the real Fylz shell (core/crates/fylz-ffi-qt, qml/Shell.qml).
# Seeds a real temp folder + journal + archive fixture, launches `fylz --smoke` under Qt's
# offscreen platform, and asserts on the values the shell logs. Needs Qt (5.15 intended for UT; only Qt 6.4.2 verified, Qt 5.15 aborted at start) + a built crate:
#   (cd core/crates/fylz-ffi-qt && cargo build) && ut/smoke.sh
# Evidence class when run in CI: CI-LANE-WRITTEN until a run is seen; when run by a human on a
# Qt box: BUILT-HERE for that box. It proves the bridge + shell logic, NOT touch/gesture feel,
# rendering on a device, or the click package.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE="$ROOT/core/crates/fylz-ffi-qt"
BIN_DIR="${FYLZ_BIN_DIR:-$CRATE/target/debug}"
APP="${FYLZ_APP:-$BIN_DIR/fylz}"   # override to smoke an installed .deb / an AppImage

eval "$("$BIN_DIR/fylz-seed-demo")"
trap 'rm -rf "$FYLZ_DEMO_BASE"' EXIT
# Put the real archive fixture inside the browsed folder so the archive view is exercised.
cp "$FYLZ_DEMO_ARCHIVE" "$FYLZ_DEMO_FOLDER/"

# QT_QUICK_BACKEND=software: a CI runner has no GPU, and under the offscreen platform the default
# (OpenGL/RHI) scene graph can wait forever for a GL context with the window visible -- the run
# then logs nothing and only `timeout` ends it (exit 124, seen on both linux-desktop legs). The
# software renderer needs no GL and exercises the same QML/bridge logic. Overridable by the caller.
export QT_QUICK_BACKEND="${QT_QUICK_BACKEND:-software}"
set +e
OUT="$(QT_QPA_PLATFORM=offscreen timeout 60 "$APP" --smoke 2>&1)"
status=$?
set -e
echo "$OUT"   # printed BEFORE the exit-status check so a hang/crash still shows its log
if [ "$status" -ne 0 ]; then
    echo "SMOKE FAIL: $APP --smoke exited with status $status (124 = hung until timeout)" >&2
    exit 1
fi

fail=0
expect() {
    if ! grep -Eq -- "$1" <<<"$OUT"; then
        echo "SMOKE FAIL: expected /$1/" >&2
        fail=1
    fi
}
expect '^qml: FYLZ_SHELL_START$'
expect '^qml: FYLZ_SHELL_WINDOW visible=true title=Fylz$'   # root is a real, shown Window
expect '^qml: FYLZ_SHELL_ROWS 7$'                         # 6 seeded entries + the archive
expect 'name=report\.TXT .*hasConflict=true'              # fylz-ops preflight NameCollision
expect 'isStaging=true'                                    # fylz-ops staging-name policy
expect '^qml: FYLZ_SHELL_VIEW archive$'                    # tapped an archive -> archive view
expect '^qml: FYLZ_SHELL_ARCHIVE_FORMAT .+'                # fylz-archive inspect reported a format
expect '^qml: FYLZ_SHELL_ARCHIVE_ROWS [1-9][0-9]*$'
expect '^qml: FYLZ_SHELL_DESCENDED .*/photos$'
expect '^qml: FYLZ_SHELL_ASCENDED .*/folder$'
expect '^qml: FYLZ_SHELL_END$'
if grep -Eq 'QML .*(Error|error)|is not a type|module .* is not installed' <<<"$OUT"; then
    echo "SMOKE FAIL: QML reported errors" >&2
    fail=1
fi
if [ "$fail" -ne 0 ]; then exit 1; fi
echo "SMOKE OK"
