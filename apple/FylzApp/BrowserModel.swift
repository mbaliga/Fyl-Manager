import Combine   // ObservableObject, @Published
import Foundation
import FylzKit

/// State for the one open archive. Main-actor isolated; every engine call runs on a detached task
/// (the core is synchronous and runs in-process on iOS: never call it from the main thread).
@MainActor
final class BrowserModel: ObservableObject {
    struct Loaded {
        var fileName: String
        var summary: ArchiveSummaryModel
        var tree: [ArchiveNode]
        var kind: String   // content-sniffed type, never the extension
    }

    enum Phase {
        case idle
        case loading(String)
        case loaded(Loaded)
        case failed(EngineFailure)
    }

    enum Extraction {
        case idle
        case running
        case done(folder: URL, ExtractionResult)
        case failed(EngineFailure)
    }

    /// The most rows the listing holds in memory for display. The summary's counts are always the
    /// full archive's; past this, the UI says the listing is cut.
    nonisolated static let maxRows = 20_000

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var extraction: Extraction = .idle
    @Published var path: [String] = []   // current directory, one component per level

    private let engine: ArchiveEngine
    private var scopedURL: URL?
    private var archiveURL: URL?

    init(engine: ArchiveEngine) { self.engine = engine }

    var engineVersion: String { engine.version() }

    /// Called with the URL the system file picker or "Open in" handed over. The security scope is
    /// kept open while the archive is shown (extraction reads it again) and released on close.
    func open(_ url: URL) {
        close()
        let granted = url.startAccessingSecurityScopedResource()
        scopedURL = granted ? url : nil
        archiveURL = url
        let name = url.lastPathComponent
        phase = .loading(name)
        let engine = self.engine
        let path = url.path
        Task.detached(priority: .userInitiated) {
            let result: Result<Loaded, EngineFailure>
            do {
                let kind = engine.sniff(path: path)
                let summary = try engine.inspect(path: path, maxRows: BrowserModel.maxRows)
                result = .success(Loaded(fileName: name, summary: summary,
                                         tree: ArchiveTree.build(summary.rows), kind: kind))
            } catch let failure as EngineFailure {
                result = .failure(failure)
            } catch {
                result = .failure(.internalError(String(describing: error)))
            }
            await MainActor.run { [weak self] in
                guard let self, self.archiveURL == url else { return }
                switch result {
                case .success(let loaded): self.phase = .loaded(loaded)
                case .failure(let failure): self.phase = .failed(failure)
                }
            }
        }
    }

    func close() {
        scopedURL?.stopAccessingSecurityScopedResource()
        scopedURL = nil
        archiveURL = nil
        phase = .idle
        extraction = .idle
        path = []
    }

    /// Extracts the whole archive into `Documents/Extracted/<name>` (visible in the Files app under
    /// "On My iPhone > Fylz"), never overwriting: a taken folder name gets " 2", " 3", ...
    func extractAll() {
        guard case .loaded(let loaded) = phase, let archiveURL else { return }
        guard case .idle = extraction else { return }
        extraction = .running
        let engine = self.engine
        let archivePath = archiveURL.path
        let fileName = loaded.fileName
        Task.detached(priority: .userInitiated) {
            let outcome: Result<(URL, ExtractionResult), EngineFailure>
            do {
                let fm = FileManager.default
                let docs = try fm.url(for: .documentDirectory, in: .userDomainMask,
                                      appropriateFor: nil, create: true)
                let root = docs.appendingPathComponent("Extracted", isDirectory: true)
                try fm.createDirectory(at: root, withIntermediateDirectories: true)
                let base = Naming.extractionFolderName(forArchiveFile: fileName)
                let name = Naming.uniqueName(base) {
                    fm.fileExists(atPath: root.appendingPathComponent($0).path)
                }
                let dest = root.appendingPathComponent(name, isDirectory: true)
                try fm.createDirectory(at: dest, withIntermediateDirectories: false)
                do {
                    let result = try engine.extractAll(archivePath: archivePath,
                                                       destinationDirectory: dest.path)
                    outcome = .success((dest, result))
                } catch {
                    // The engine removes what it wrote; remove the folder we made for it too,
                    // but only if it is empty (`removeItem` on a non-empty dir would recurse).
                    if (try? fm.contentsOfDirectory(atPath: dest.path))?.isEmpty == true {
                        try? fm.removeItem(at: dest)
                    }
                    throw error
                }
            } catch let failure as EngineFailure {
                outcome = .failure(failure)
            } catch {
                outcome = .failure(.io(error.localizedDescription))
            }
            await MainActor.run { [weak self] in
                guard let self else { return }
                switch outcome {
                case .success(let (dest, result)): self.extraction = .done(folder: dest, result)
                case .failure(let failure): self.extraction = .failed(failure)
                }
            }
        }
    }

    func dismissExtraction() { extraction = .idle }
}
