import XCTest
@testable import FylzKit

final class ByteFormatTests: XCTestCase {
    func testSmallAndLarge() {
        XCTAssertEqual(ByteFormat.string(0), "0 B")
        XCTAssertEqual(ByteFormat.string(999), "999 B")
        XCTAssertEqual(ByteFormat.string(1000), "1.0 KB")
        XCTAssertEqual(ByteFormat.string(1_500_000), "1.5 MB")
        XCTAssertEqual(ByteFormat.string(2_000_000_000), "2.0 GB")
    }
    func testRoundingCarriesIntoNextUnit() {
        XCTAssertEqual(ByteFormat.string(999_960), "1.0 MB")
    }
}

final class ArchiveTreeTests: XCTestCase {
    private func row(_ ordinal: UInt32, _ path: String, _ kind: EntryKind = .file,
                     size: UInt64? = 1) -> ArchiveRowModel {
        ArchiveRowModel(ordinal: ordinal, path: path, kind: kind, uncompressed: size)
    }

    func testImpliedDirectoriesAreSynthesised() {
        let tree = ArchiveTree.build([row(0, "docs/notes/a.txt")])
        XCTAssertEqual(tree.map(\.name), ["docs"])
        XCTAssertTrue(tree[0].implied)
        XCTAssertEqual(tree[0].children[0].name, "notes")
        XCTAssertEqual(tree[0].children[0].children[0].path, "docs/notes/a.txt")
    }

    func testExplicitDirectoryReplacesImpliedOne() {
        let tree = ArchiveTree.build([row(0, "docs/a.txt"), row(1, "docs/", .directory, size: nil)])
        XCTAssertEqual(tree.count, 1)
        XCTAssertFalse(tree[0].implied)
    }

    func testDirectoriesFirstThenNumericCaseInsensitive() {
        let tree = ArchiveTree.build([
            row(0, "file10.txt"), row(1, "File2.txt"), row(2, "zdir/x", .file), row(3, "adir/y"),
        ])
        XCTAssertEqual(tree.map(\.name), ["adir", "zdir", "File2.txt", "file10.txt"])
    }

    func testBackslashesAndDotPrefixesNormalise() {
        XCTAssertEqual(ArchiveTree.normalise(".\\a\\\\b/./c/"), ["a", "b", "c"])
        let tree = ArchiveTree.build([row(0, "a\\b.txt")])
        XCTAssertEqual(tree[0].children[0].path, "a/b.txt")
    }

    func testDuplicatePathKeepsFirst() {
        let tree = ArchiveTree.build([row(0, "x.txt", size: 1), row(1, "x.txt", size: 2)])
        XCTAssertEqual(tree.count, 1)
        XCTAssertEqual(tree[0].size, 1)
    }

    func testEmptyPathsAreIgnored() {
        XCTAssertTrue(ArchiveTree.build([row(0, ""), row(1, "/"), row(2, ".")]).isEmpty)
    }

    func testChildrenLookup() {
        let tree = ArchiveTree.build([row(0, "a/b/c.txt"), row(1, "a/d.txt")])
        XCTAssertEqual(ArchiveTree.children(of: "", in: tree)?.map(\.name), ["a"])
        XCTAssertEqual(ArchiveTree.children(of: "a", in: tree)?.map(\.name), ["b", "d.txt"])
        XCTAssertNil(ArchiveTree.children(of: "a/d.txt", in: tree))
        XCTAssertNil(ArchiveTree.children(of: "nope", in: tree))
    }

    func testLossyAndEncryptedFlagsSurvive() {
        let r = ArchiveRowModel(ordinal: 0, path: "bad\u{FFFD}.txt", nameLossy: true, kind: .file,
                                encrypted: true)
        let node = ArchiveTree.build([r])[0]
        XCTAssertTrue(node.nameLossy)
        XCTAssertTrue(node.encrypted)
    }
}

final class NamingTests: XCTestCase {
    func testFolderNameStripsArchiveExtensions() {
        XCTAssertEqual(Naming.extractionFolderName(forArchiveFile: "photos.tar.gz"), "photos")
        XCTAssertEqual(Naming.extractionFolderName(forArchiveFile: "Report.ZIP"), "Report")
        XCTAssertEqual(Naming.extractionFolderName(forArchiveFile: "a.b.7z"), "a.b")
        XCTAssertEqual(Naming.extractionFolderName(forArchiveFile: "noext"), "noext")
    }
    func testFolderNameNeverEmptyHiddenOrPathLike() {
        XCTAssertEqual(Naming.extractionFolderName(forArchiveFile: ".zip"), "Extracted")
        XCTAssertEqual(Naming.extractionFolderName(forArchiveFile: "..zip"), "Extracted")
        XCTAssertEqual(Naming.extractionFolderName(forArchiveFile: "a/b.zip"), "a-b")
        XCTAssertEqual(Naming.extractionFolderName(forArchiveFile: ".hidden.zip"), "hidden")
    }
    func testUniqueNameKeepsBoth() {
        let taken: Set<String> = ["x", "x 2", "x 3"]
        XCTAssertEqual(Naming.uniqueName("y", exists: { taken.contains($0) }), "y")
        XCTAssertEqual(Naming.uniqueName("x", exists: { taken.contains($0) }), "x 4")
    }
}

final class FailurePresenterTests: XCTestCase {
    func testEveryCaseHasTitleAndDetail() {
        let all: [EngineFailure] = [
            .unsupported("u"), .corrupt("c"), .refused("r"), .limitExceeded(entry: "e", rule: "file"),
            .unsafePath("p"), .destinationExists("d"), .io("i"), .failed("f"), .internalError("b"),
        ]
        for failure in all {
            XCTAssertFalse(FailurePresenter.title(failure).isEmpty)
            XCTAssertFalse(FailurePresenter.detail(failure).isEmpty)
        }
    }
    func testRefusalShowsThePolicyReasonVerbatim() {
        let text = FailurePresenter.detail(.refused("Archive contains an unsafe path segment."))
        XCTAssertTrue(text.hasPrefix("Archive contains an unsafe path segment."))
        XCTAssertTrue(text.contains("Nothing was written"))
    }
}
