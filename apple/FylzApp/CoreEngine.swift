import Foundation
import FylzKit
// The uniffi-generated apple/Generated/FylzCore.swift (apple/scripts/build-xcframework.sh, not
// committed) is compiled into this app target, so its API is in scope without an import: there is
// no separate `FylzCore` module to import.

/// The real engine: a thin adapter from the Rust core (via the generated `FylzCore` module) to
/// FylzKit's `ArchiveEngine`. All the logic lives in Rust; this file only renames types and
/// translates errors, so it is the one place the generated names (`FylzError.Io`, ...) appear.
struct CoreEngine: ArchiveEngine {
    func version() -> String { fylzVersion() }

    func sniff(path: String) -> String { sniffPath(path: path) }

    func inspect(path: String, maxRows: Int) throws -> ArchiveSummaryModel {
        do {
            let s = try archiveInspect(path: path, limits: archiveDefaultLimits(),
                                       maxRows: UInt32(clamping: maxRows))
            return ArchiveSummaryModel(
                archiveBytes: s.archiveBytes, formatName: s.formatName, filters: s.filters,
                entryCount: Int(s.entryCount), fileCount: Int(s.fileCount),
                directoryCount: Int(s.directoryCount), linkCount: Int(s.linkCount),
                totalUncompressed: s.totalUncompressed, hasEncryptedEntries: s.hasEncryptedEntries,
                hasLossyNames: s.hasLossyNames, extractionAllowed: s.extractionAllowed,
                refusalReason: s.refusalReason, rows: s.rows.map(Self.row),
                rowsTruncated: s.rowsTruncated)
        } catch let error as FylzError {
            throw Self.failure(error)
        }
    }

    func extractAll(archivePath: String, destinationDirectory: String) throws -> ExtractionResult {
        do {
            let r = try archiveExtractAll(archivePath: archivePath, destDir: destinationDirectory,
                                          limits: archiveDefaultLimits())
            return ExtractionResult(entriesWritten: Int(r.entriesWritten),
                                    bytesWritten: r.bytesWritten, skippedLinks: Int(r.skippedLinks))
        } catch let error as FylzError {
            throw Self.failure(error)
        }
    }

    private static func row(_ r: ArchiveRow) -> ArchiveRowModel {
        let kind: EntryKind
        switch r.kind {
        case .file: kind = .file
        case .directory: kind = .directory
        case .symlink: kind = .symlink
        case .hardlink: kind = .hardlink
        case .other: kind = .other
        }
        return ArchiveRowModel(ordinal: r.ordinal, path: r.path, nameLossy: r.nameLossy, kind: kind,
                               linkTarget: r.linkTarget, uncompressed: r.uncompressed,
                               mtime: r.mtime, encrypted: r.encrypted)
    }

    private static func failure(_ e: FylzError) -> EngineFailure {
        switch e {
        case .Unsupported(let d): return .unsupported(d)
        case .Corrupt(let d): return .corrupt(d)
        case .Refused(let r): return .refused(r)
        case .LimitExceeded(let entry, let rule): return .limitExceeded(entry: entry, rule: rule)
        case .UnsafePath(let p): return .unsafePath(p)
        case .DestinationExists(let p): return .destinationExists(p)
        case .Io(let d): return .io(d)
        case .Failed(let d): return .failed(d)
        case .Internal(let d): return .internalError(d)
        }
    }
}
