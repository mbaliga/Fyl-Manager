# Fylz for Files (iOS / iPadOS)

**This is a document browser for archives. It is not the Fylz file manager and it is not a port of
the Android app.** iOS gives an app no way to manage the device's files, so the Android product
cannot exist here; this app runs the same Rust archive engine and shows it through SwiftUI.
Design, decisions and evidence labels: [`docs/design/ios-fylz-for-files.md`](../docs/design/ios-fylz-for-files.md).

What it does: open an archive you pick (or "Open in Fylz"), identify it by content, list it, show
whether extraction is allowed *and why not* if it is not, and extract it into
`On My iPhone / Fylz / Extracted / <name>` without overwriting anything.

Privacy: no network, no analytics, no accounts. The only crash reports are the ones **Apple**
collects for TestFlight / App Store installs if you allow that in iOS Settings; the app sends
nothing itself.

## Build (needs a Mac with Xcode 15+; CI does the same on `macos-latest`)

```sh
git submodule update --init core/third_party/libarchive core/third_party/zlib \
  core/third_party/bzip2 core/third_party/xz core/third_party/zstd core/third_party/lz4
brew install xcodegen
apple/scripts/build-xcframework.sh          # Rust core -> XCFramework + Swift bindings
(cd apple && xcodegen generate --spec project.yml)
open apple/FylzForFiles.xcodeproj           # or xcodebuild, see .github/workflows/apple-ios.yml
(cd apple/FylzKit && swift test)            # the pure-Swift logic
```

The bundle id (`com.asoc.fylz`) lives only in `Config/Bundle.xcconfig`. No signing material is in
the repo. **Nothing here has been compiled or run on an Apple platform**; see `OWNER_VERIFY.md`.
