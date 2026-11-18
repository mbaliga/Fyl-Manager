# Ubuntu Touch: what only the owner can verify

Nothing below has been done. Every row is NEEDS-DEVICE-VALIDATION unless stated.

## Before the first run
1. Decide the device image. The OnePlus 6 is read as 20.04-only (plan OQ-33/OQ-37); this click
   targets `ubuntu-touch-24.04-1.x`. If the phone runs 20.04, change `framework` in
   `clickable.yaml` and `ut/manifest.json` to a 20.04 framework and rebuild.
2. Edit `ut/manifest.json`: `maintainer` is a `users.noreply` address (use a real contact for the
   OpenStore) and confirm the app name `fylz.mbaliga`.
3. Push a tag `ut-v0.1.0` (or run the workflow manually); the `click` job uploads
   `fylz-click-arm64` / `fylz-click-amd64` on tags only. If it fails, start with section 4 of
   `docs/design/ubuntu-touch-port.md`.

## On the device
- `clickable install` (or `adb push` + `pkcon install-local`), launch Fylz.
- Does `Exec=fylz` launch (expected: `lib/aarch64-linux-gnu/bin` is on PATH)?
- Does the list show your home folder (HOME is read; unconfined policy)? Tap a folder: descend?
  Up: ascend? Tap a `.zip`/`.tar.gz`: archive entries listed? Back returns?
- Touch targets, scaling and text size at the phone's grid unit (QML uses fixed 48 px targets).
- Does a corrupt archive show "Cannot open this archive: ..." and not crash?
- `clickable review` warnings (unconfined is expected to need a manual-review note on OpenStore).

## Desktop test
- `clickable desktop --arch amd64`, or run the CI-built amd64 click.

## Not owner-verifiable yet
Copy/move/delete, rename, search, thumbnails, rooms IA: not built.
