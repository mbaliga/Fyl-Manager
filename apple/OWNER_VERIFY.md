# Owner checklist: Fylz for Files (iOS)

Every item is NEEDS-DEVICE-VALIDATION unless stated. Nothing in `apple/` has been compiled or run.

## Before anything builds
1. Apple Developer Program enrolment (OQ-2); an App Store Connect app record for `com.asoc.fylz`.
2. Add the `NAMES.md` row for `com.asoc.fylz` (plan rule R11); `NAMES.md` is outside this repo.
3. Push, then watch `.github/workflows/apple-ios.yml`. It is CI-LANE-WRITTEN: expect first-run
   fixes. Private repo: hosted macOS minutes are billed.
4. Likely first failures, in order: the cmake configure of libarchive/zstd for iOS (sim runner
   sdk probing), `xcodebuild -create-xcframework` header layout, XcodeGen `info:` merge with
   `GENERATE_INFOPLIST_FILE`, link flags in `OTHER_LDFLAGS`, the generated Swift case names in
   `CoreEngine.swift`.

## On a device / simulator (iPhone and the iPad Pro M4)
- Open a ZIP, a `.tar.gz`, a `.7z` and a `.iso` from the Files app; the listing and counts match.
- A renamed file (`photo.jpg` that is really a zip) is identified by content.
- `zip-slip.zip` (`core/fixtures/archives/hostile/`) lists, shows the refusal reason, and
  Extract is disabled; nothing is written.
- Extract a normal archive: files appear under On My iPhone / Fylz / Extracted / name; extracting
  again gives "name 2"; a failing archive (`core/fixtures/archives/crc-bad.zip`) leaves nothing.
- A multi-hundred-MB archive: memory stays sane (the core runs in-process, no isolation; section
  4 of the design doc). Watch for a jetsam kill.
- "Open in Fylz" from Mail/Safari; security-scoped access released on Close.
- Locale pin on iOS: archive *creation* is not exposed in the UI, but the core's `newlocale` path
  must be checked if creation is added (design doc section 3).
- About screen text matches reality; Settings shows no network use for the app.

## Decisions waiting on you
- Accept in-process decoding (design doc section 4) or require a spike for a separate process.
- Keep v0.1 delete-free, or define an iOS recycle contract (section 5).
- File Provider extension: spike now or later (4 weeks estimated, memory budget unknown).
- Rebuild the rooms / word-wheel navigation in SwiftUI, or keep the plain list.
