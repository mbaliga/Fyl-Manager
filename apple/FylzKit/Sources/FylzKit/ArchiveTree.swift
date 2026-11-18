import Foundation

/// A node in the browsable tree built from a flat entry listing.
public struct ArchiveNode: Identifiable, Equatable, Sendable {
    public var id: String { path }
    /// Full path from the archive root, no trailing slash, "/"-separated.
    public var path: String
    public var name: String
    public var kind: EntryKind
    public var size: UInt64?
    public var mtime: Int64?
    public var linkTarget: String?
    public var encrypted: Bool
    public var nameLossy: Bool
    /// True when the archive had no entry for this directory and it exists only because a deeper
    /// entry's path implies it (zip files often omit directory entries).
    public var implied: Bool
    public var children: [ArchiveNode]

    public var isDirectory: Bool { kind == .directory }
}

public enum ArchiveTree {
    /// Normalises an archive entry path for display and grouping: backslashes become "/", leading
    /// "./" and "/" and trailing "/" are dropped, empty and "." components vanish. Never resolves
    /// "..": the engine's policy refuses those, so a surviving ".." is shown literally.
    public static func normalise(_ path: String) -> [String] {
        path.replacingOccurrences(of: "\\", with: "/")
            .split(separator: "/", omittingEmptySubsequences: true)
            .map(String.init)
            .filter { $0 != "." }
    }

    /// Builds the tree. Directories sort before files, then names compare case-insensitively with
    /// a numeric-aware ordering ("file2" before "file10"), stable on ties. Duplicate paths keep
    /// the first row (the engine's policy refuses duplicate keys before extraction, but a listing
    /// can still show them).
    public static func build(_ rows: [ArchiveRowModel]) -> [ArchiveNode] {
        final class Builder {
            var node: ArchiveNode
            var kids: [String: Builder] = [:]
            var order: [String] = []
            init(_ node: ArchiveNode) { self.node = node }
        }
        let root = Builder(ArchiveNode(path: "", name: "", kind: .directory, size: nil, mtime: nil,
                                       linkTarget: nil, encrypted: false, nameLossy: false,
                                       implied: false, children: []))
        for row in rows {
            let parts = normalise(row.path)
            if parts.isEmpty { continue }
            var cursor = root
            for (index, part) in parts.enumerated() {
                let isLast = index == parts.count - 1
                let path = parts[0...index].joined(separator: "/")
                if let existing = cursor.kids[part] {
                    if isLast && existing.node.implied {
                        existing.node.kind = row.kind
                        existing.node.size = row.uncompressed
                        existing.node.mtime = row.mtime
                        existing.node.linkTarget = row.linkTarget
                        existing.node.encrypted = row.encrypted
                        existing.node.nameLossy = row.nameLossy
                        existing.node.implied = false
                    }
                    cursor = existing
                } else {
                    let node: ArchiveNode
                    if isLast {
                        node = ArchiveNode(path: path, name: part, kind: row.kind,
                                           size: row.uncompressed, mtime: row.mtime,
                                           linkTarget: row.linkTarget, encrypted: row.encrypted,
                                           nameLossy: row.nameLossy, implied: false, children: [])
                    } else {
                        node = ArchiveNode(path: path, name: part, kind: .directory, size: nil,
                                           mtime: nil, linkTarget: nil, encrypted: false,
                                           nameLossy: false, implied: true, children: [])
                    }
                    let child = Builder(node)
                    cursor.kids[part] = child
                    cursor.order.append(part)
                    cursor = child
                }
            }
        }
        func finish(_ builder: Builder) -> ArchiveNode {
            var node = builder.node
            let kids = builder.order.compactMap { builder.kids[$0] }.map(finish)
            node.children = kids.enumerated().sorted { lhs, rhs in
                let (li, l) = lhs, (ri, r) = rhs
                if l.isDirectory != r.isDirectory { return l.isDirectory }
                let cmp = l.name.compare(r.name, options: [.caseInsensitive, .numeric])
                if cmp != .orderedSame { return cmp == .orderedAscending }
                return li < ri
            }.map { $0.element }
            return node
        }
        return finish(root).children
    }

    /// The nodes under `path` ("" for the root), or nil if `path` names no directory.
    public static func children(of path: String, in tree: [ArchiveNode]) -> [ArchiveNode]? {
        let parts = normalise(path)
        var level = tree
        for part in parts {
            guard let next = level.first(where: { $0.name == part && $0.isDirectory }) else {
                return nil
            }
            level = next.children
        }
        return level
    }
}
