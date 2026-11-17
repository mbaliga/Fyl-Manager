import Foundation

public enum Naming {
    /// Compound extensions first, so "photos.tar.gz" becomes "photos", not "photos.tar".
    private static let archiveExtensions = [
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.lzma",
        ".tgz", ".tbz2", ".tbz", ".txz", ".tzst",
        ".zip", ".7z", ".rar", ".tar", ".iso", ".cpio", ".ar", ".gz", ".bz2", ".xz", ".zst",
        ".lz4", ".cab", ".jar", ".apk", ".epub",
    ]

    /// The folder name an archive extracts into: the file name without its archive extension,
    /// and never empty or path-like.
    public static func extractionFolderName(forArchiveFile fileName: String) -> String {
        let lower = fileName.lowercased()
        var base = fileName
        for ext in archiveExtensions where lower.hasSuffix(ext) && fileName.count >= ext.count {
            base = String(fileName.dropLast(ext.count))
            break
        }
        let cleaned = base
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
            .replacingOccurrences(of: "\0", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        // A leading dot would make the folder hidden in Files; a bare "." or ".." is a path.
        let unhidden = String(cleaned.drop(while: { $0 == "." }))
        return unhidden.isEmpty ? "Extracted" : unhidden
    }

    /// Keep-both naming, the way the Android app and Finder do it: "name", then "name 2",
    /// "name 3", ... until `exists` says the candidate is free. Nothing is ever overwritten.
    public static func uniqueName(_ name: String, exists: (String) -> Bool) -> String {
        if !exists(name) { return name }
        var n = 2
        while exists("\(name) \(n)") { n += 1 }
        return "\(name) \(n)"
    }
}
