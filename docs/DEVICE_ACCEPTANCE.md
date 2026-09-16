# Device and provider acceptance

Stable v1 requires execution of this matrix on real Android environments. Unit tests and desktop lint cannot validate provider behavior, persisted grants, background scheduling or OEM lifecycle restrictions.

For every run, record the app commit, device, Android version, provider version, storage type and exact result. Use non-sensitive fixtures.

## Required environments

The app's minSdk is 31 (set by the Hyle design system); nothing below Android 12 can install it.

- Android 12 / API 31 (the minimum)
- Android 13 / API 33 (first version with the runtime notification permission)
- Android 14 / API 34
- Android 15 / API 35
- Android 16 / API 36 (the target)
- Small phone, landscape phone, tablet or foldable-sized window
- Hardware keyboard and pointer-capable environment

## Required providers

- Android local DocumentsProvider
- Removable SD card where supported
- USB storage where supported
- At least one cloud DocumentsProvider
- Google Drive provider when available
- A provider that refuses or lacks rename/delete support
- A provider that reports unknown file sizes or available capacity

## Core file journeys

- Open and persist multiple roots.
- Navigate, search, switch tabs and reopen after process death.
- Create files and folders.
- Rename a file and folder.
- Copy and move single and multiple files.
- Exercise keep-both, skip and guarded replacement.
- Force a move where destination succeeds and source deletion fails; verify **Finish move** never copies again.
- Recycle, restore and permanently delete with explicit confirmation.
- Interrupt copy/move and inspect the operation journal after restart.

## File history

- Enable and disable history.
- Verify per-file, file-size and total-storage limits.
- Save an eligible text file and confirm pre-write capture.
- Restore a previous version and verify the current version was preserved first.
- Force a restore verification failure and confirm rollback behavior.
- Rename/move a tracked file through providers that return stable and changed URIs.
- Fill the quota and verify oldest-first pruning without orphaned blobs.

## Backups

- Manual backup of nested folders and mixed file sizes.
- Daily schedule under normal operation and Doze.
- Media-threshold schedule before and after reboot.
- Charging, idle, battery-not-low, storage-not-low and network constraints.
- Revoke source/destination permissions and verify actionable failure.
- Disconnect SD/USB destination during a run and verify staging cleanup or `NEEDS_ATTENTION`.
- Attempt overlapping manual and scheduled runs; verify the per-plan lease permits only one.
- Restore a verified snapshot into a new folder.
- Reinstall/clear app data, select the external destination and rediscover backups from manifests.
- Change timezone and confirm the next daily eligibility window is reconciled.

## Archives

- Create a standard ZIP.
- Create an AES-256 protected ZIP with an 8+ character password.
- Confirm wrong-password extraction fails without partial provider output.
- Inspect and extract valid nested archives.
- Exercise low cache space and low provider space where reported.
- Test traversal, absolute path, case collision, duplicate path, oversized entry, entry-count, depth and compression-ratio rejection.
- Interrupt extraction and verify rollback of the newly created destination folder.

## Permissions and first run

- Decline "All files access" on first launch, confirm the Storage Access Framework fallback works, then grant it from Settings and confirm the volumes appear without a picker.
- Confirm the per-app "All files access" screen opens (not the device-wide list) on API 31+.
- Enable a scheduled backup plan on API 33+ and confirm the notification permission prompt appears once and the progress notification shows during a run.

## Documents, media and annotation

- Annotate a JPEG/PNG/WebP, a multi-page PDF and a DXF; open each saved output in a third-party viewer (a PDF reader, a CAD viewer) and confirm the ink is present and positioned where it was drawn.
- Merge, split and export PDF pages; export pages as PNG/JPEG; run OCR on a scanned PDF and search its text.
- Play a video with embedded subtitles and multiple audio tracks; switch tracks, change speed, adjust the equalizer, and confirm audio focus/interruption behaviour on a call.
- Add a folder to the local index, wait for the rebuild to finish, and search for a phrase that appears only inside a PDF and a .docx in that folder.

## Remote providers

- Connect to SFTP, SMB, WebDAV and an S3-compatible endpoint; browse, preview and copy a file to local storage; confirm a wrong password fails cleanly and no secret appears in logs or exports.

## Accessibility and adaptive UI

- TalkBack navigation through Files and Recovery destinations.
- 200% font scale without clipped actions or inaccessible dialogs.
- Keyboard-only navigation, activation and dismissal.
- Pointer hover/click/double-click/long-click equivalents.
- Portrait, landscape, split-screen, tablet and foldable layouts.
- Visible focus, meaningful content descriptions and no colour-only status communication.

## Release and upgrade

- Clean install of signed candidate.
- Upgrade from previous signed candidate.
- Reboot after upgrade and verify WorkManager reconciliation.
- Confirm app-private backup/history metadata is excluded from cloud backup/device transfer.
- Verify release APK certificate and SHA-256.
- Review release lint and dependency artifacts.

A failed data-integrity, permission-boundary, credential, rollback or recovery case blocks stable release.
