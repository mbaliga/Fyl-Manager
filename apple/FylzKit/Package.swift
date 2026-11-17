// swift-tools-version:5.9
// FylzKit: the pure-Swift logic of "Fylz for Files" (iOS). No UIKit, no SwiftUI, no FFI import, so
// it builds and tests on Linux as well as on macOS. The app target adapts the uniffi-generated
// FylzCore module to `ArchiveEngine` (apple/FylzApp/CoreEngine.swift).
import PackageDescription

let package = Package(
    name: "FylzKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "FylzKit", targets: ["FylzKit"])],
    targets: [
        .target(name: "FylzKit"),
        .testTarget(name: "FylzKitTests", dependencies: ["FylzKit"]),
    ]
)
