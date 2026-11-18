import SwiftUI
import UniformTypeIdentifiers
import FylzKit

struct RootView: View {
    @EnvironmentObject var model: BrowserModel
    @State private var picking = false
    @State private var showAbout = false

    var body: some View {
        NavigationStack {
            Group {
                switch model.phase {
                case .idle: IdleView(pick: { picking = true })
                case .loading(let name):
                    VStack(spacing: 12) { ProgressView(); Text("Reading \(name)").font(.callout) }
                case .loaded(let loaded): ArchiveView(loaded: loaded)
                case .failed(let failure): FailureView(failure: failure, retry: { picking = true })
                }
            }
            .navigationTitle("Fylz for Files")
            // navigationBar* placements, not topBar*: the latter need iOS 17 and this targets 16.
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { showAbout = true } label: { Label("About", systemImage: "info.circle") }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { picking = true } label: { Label("Open archive", systemImage: "folder") }
                }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first { model.open(url) }
        }
        .sheet(isPresented: $showAbout) { AboutView() }
    }
}

struct IdleView: View {
    let pick: () -> Void
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "archivebox").font(.system(size: 56)).accessibilityHidden(true)
            Text("Open an archive to look inside it.").font(.headline)
            Text("ZIP, 7z, TAR (gzip, bzip2, xz, zstd, lz4), ISO and more. Fylz reads the file's content, not its name, and shows what an archive holds before anything is written.")
                .font(.callout).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button("Choose a file", action: pick).buttonStyle(.borderedProminent)
        }
        .padding(24)
    }
}

struct FailureView: View {
    let failure: EngineFailure
    let retry: () -> Void
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 44)).accessibilityHidden(true)
            Text(FailurePresenter.title(failure)).font(.headline)
            Text(FailurePresenter.detail(failure)).font(.callout).multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("Choose another file", action: retry)
        }
        .padding(24)
    }
}

struct ArchiveView: View {
    @EnvironmentObject var model: BrowserModel
    let loaded: BrowserModel.Loaded

    private var directory: [ArchiveNode] {
        ArchiveTree.children(of: model.path.joined(separator: "/"), in: loaded.tree) ?? []
    }

    var body: some View {
        List {
            Section {
                SummaryRows(loaded: loaded)
                if !loaded.summary.extractionAllowed {
                    Label(loaded.summary.refusalReason ?? "Extraction is refused by policy.",
                          systemImage: "hand.raised")
                        .foregroundStyle(.orange)
                }
                ExtractRow(allowed: loaded.summary.extractionAllowed)
            }
            Section(header: Text(model.path.isEmpty ? "Contents" : "/" + model.path.joined(separator: "/"))) {
                if !model.path.isEmpty {
                    Button { model.path.removeLast() } label: { Label("Up", systemImage: "arrow.up.left") }
                }
                ForEach(directory) { node in
                    if node.isDirectory {
                        Button { model.path.append(node.name) } label: { NodeRow(node: node) }
                            .buttonStyle(.plain)
                    } else {
                        NodeRow(node: node)
                    }
                }
                if loaded.summary.rowsTruncated {
                    Text("Only the first \(loaded.summary.rows.count) of \(loaded.summary.entryCount) entries are listed.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
    }
}

struct SummaryRows: View {
    let loaded: BrowserModel.Loaded
    var body: some View {
        let s = loaded.summary
        VStack(alignment: .leading, spacing: 4) {
            Text(loaded.fileName).font(.headline)
            Text("\(loaded.kind) \u{00B7} \(ByteFormat.string(s.archiveBytes))").font(.footnote)
            Text("\(s.fileCount) files, \(s.directoryCount) folders" + (s.linkCount > 0 ? ", \(s.linkCount) links (never extracted)" : ""))
                .font(.footnote)
            if let total = s.totalUncompressed {
                Text("Unpacks to \(ByteFormat.string(total))").font(.footnote)
            }
            if s.hasEncryptedEntries { Label("Contains encrypted entries", systemImage: "lock").font(.footnote) }
            if s.hasLossyNames { Label("Some names are not valid text", systemImage: "textformat.abc").font(.footnote) }
        }
    }
}

struct ExtractRow: View {
    @EnvironmentObject var model: BrowserModel
    let allowed: Bool
    var body: some View {
        switch model.extraction {
        case .idle:
            Button { model.extractAll() } label: { Label("Extract to Fylz folder", systemImage: "square.and.arrow.down") }
                .disabled(!allowed)
        case .running:
            HStack { ProgressView(); Text("Extracting\u{2026}") }
        case .done(let folder, let result):
            VStack(alignment: .leading, spacing: 4) {
                Label("Extracted \(result.entriesWritten) files (\(ByteFormat.string(result.bytesWritten)))",
                      systemImage: "checkmark.circle")
                Text("In Files: On My iPhone / Fylz / Extracted / \(folder.lastPathComponent)").font(.footnote)
                Button("Done") { model.dismissExtraction() }
            }
        case .failed(let failure):
            VStack(alignment: .leading, spacing: 4) {
                Label(FailurePresenter.title(failure), systemImage: "xmark.octagon")
                Text(FailurePresenter.detail(failure)).font(.footnote)
                Button("Dismiss") { model.dismissExtraction() }
            }
        }
    }
}

struct NodeRow: View {
    let node: ArchiveNode
    var body: some View {
        HStack {
            Image(systemName: icon).frame(width: 24).accessibilityHidden(true)
            VStack(alignment: .leading) {
                Text(node.name).lineLimit(1)
                if node.nameLossy { Text("name is not valid text").font(.caption2).foregroundStyle(.orange) }
                if let target = node.linkTarget { Text("\u{2192} \(target)").font(.caption2).foregroundStyle(.secondary) }
            }
            Spacer()
            if node.encrypted { Image(systemName: "lock").accessibilityLabel("Encrypted") }
            if let size = node.size, !node.isDirectory {
                Text(ByteFormat.string(size)).font(.footnote).foregroundStyle(.secondary)
            }
            if node.isDirectory { Image(systemName: "chevron.right").foregroundStyle(.secondary).accessibilityHidden(true) }
        }
    }
    private var icon: String {
        switch node.kind {
        case .directory: return "folder"
        case .symlink, .hardlink: return "link"
        case .other: return "questionmark.square"
        case .file: return "doc"
        }
    }
}

struct AboutView: View {
    @EnvironmentObject var model: BrowserModel
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                Section("What this is") {
                    Text("Fylz for Files is a document browser for archives. It is not the Fylz file manager from Android, and it is not a port of it: iOS does not let an app manage the device's files, so this app only opens what you hand it.")
                }
                Section("What it never does") {
                    Text("No network access, no accounts, no analytics, no ads. It cannot delete your files.")
                }
                Section("Crash reports") {
                    Text("If you install through TestFlight or the App Store, Apple may collect crash reports from your device if you have allowed that in iOS Settings. The app itself sends nothing anywhere.")
                }
                Section("Safety") {
                    Text("Archives are decoded inside this app, not in a separate sandboxed process (iOS has none). Extraction is limited by size, count and compression-ratio rules, never overwrites, and removes anything it wrote if it fails.")
                }
                Section("Version") { Text("Core \(model.engineVersion)") }
            }
            .navigationTitle("About")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
