import Foundation

/// What an archive entry is. Mirrors `fylz_archive::EntryKind` as the Rust bindings expose it.
public enum EntryKind: Equatable, Sendable {
    case file, directory, symlink, hardlink, other
}

/// One listing row, decoupled from the generated FFI types so this package stays pure Swift.
public struct ArchiveRowModel: Equatable, Sendable {
    public var ordinal: UInt32
    public var path: String
    public var nameLossy: Bool
    public var kind: EntryKind
    public var linkTarget: String?
    public var uncompressed: UInt64?
    public var mtime: Int64?
    public var encrypted: Bool

    public init(ordinal: UInt32, path: String, nameLossy: Bool = false, kind: EntryKind,
                linkTarget: String? = nil, uncompressed: UInt64? = nil, mtime: Int64? = nil,
                encrypted: Bool = false) {
        self.ordinal = ordinal; self.path = path; self.nameLossy = nameLossy; self.kind = kind
        self.linkTarget = linkTarget; self.uncompressed = uncompressed; self.mtime = mtime
        self.encrypted = encrypted
    }
}

public struct ArchiveSummaryModel: Equatable, Sendable {
    public var archiveBytes: UInt64
    public var formatName: String?
    public var filters: [String]
    public var entryCount: Int
    public var fileCount: Int
    public var directoryCount: Int
    public var linkCount: Int
    public var totalUncompressed: UInt64?
    public var hasEncryptedEntries: Bool
    public var hasLossyNames: Bool
    public var extractionAllowed: Bool
    public var refusalReason: String?
    public var rows: [ArchiveRowModel]
    public var rowsTruncated: Bool

    public init(archiveBytes: UInt64, formatName: String? = nil, filters: [String] = [],
                entryCount: Int, fileCount: Int, directoryCount: Int, linkCount: Int,
                totalUncompressed: UInt64? = nil, hasEncryptedEntries: Bool = false,
                hasLossyNames: Bool = false, extractionAllowed: Bool, refusalReason: String? = nil,
                rows: [ArchiveRowModel], rowsTruncated: Bool = false) {
        self.archiveBytes = archiveBytes; self.formatName = formatName; self.filters = filters
        self.entryCount = entryCount; self.fileCount = fileCount
        self.directoryCount = directoryCount; self.linkCount = linkCount
        self.totalUncompressed = totalUncompressed; self.hasEncryptedEntries = hasEncryptedEntries
        self.hasLossyNames = hasLossyNames; self.extractionAllowed = extractionAllowed
        self.refusalReason = refusalReason; self.rows = rows; self.rowsTruncated = rowsTruncated
    }
}

public struct ExtractionResult: Equatable, Sendable {
    public var entriesWritten: Int
    public var bytesWritten: UInt64
    public var skippedLinks: Int
    public init(entriesWritten: Int, bytesWritten: UInt64, skippedLinks: Int) {
        self.entriesWritten = entriesWritten; self.bytesWritten = bytesWritten
        self.skippedLinks = skippedLinks
    }
}

/// Every way the engine can fail, as the UI needs to tell them apart. Mirrors the Rust `FylzError`.
public enum EngineFailure: Error, Equatable, Sendable {
    case unsupported(String)
    case corrupt(String)
    case refused(String)
    case limitExceeded(entry: String, rule: String)
    case unsafePath(String)
    case destinationExists(String)
    case io(String)
    case failed(String)
    case internalError(String)
}

/// The seam between the UI and the Rust core. The app provides the real implementation (over the
/// generated FylzCore module); tests provide fakes. Calls are synchronous and CPU/IO heavy: the
/// caller must run them off the main thread (the core runs in-process on iOS, with no isolated
/// decoder process; see docs/design/ios-fylz-for-files.md section 4).
public protocol ArchiveEngine: Sendable {
    func version() -> String
    func sniff(path: String) -> String
    func inspect(path: String, maxRows: Int) throws -> ArchiveSummaryModel
    func extractAll(archivePath: String, destinationDirectory: String) throws -> ExtractionResult
}
