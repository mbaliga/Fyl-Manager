// The minimal M13.3 proof-of-bridge slice (docs/agent/ADR-LINUX-UT-STRATEGY.md): NOT the full
// UI docs/agent/MASTER_PLAN.md describes (Android's rooms IA, the edge scrubber, Quick Look, a
// full Hyle-for-QML theme, tablet/desktop convergence) -- that stays real, separate future work.
// This screen's only job is proving the fylz-ffi-qt bridge is real and functional: it loads a
// real folder through FolderModel, a real fylz-archive fixture through ArchiveModel, and a real
// fylz-ops journal through OperationQueue, then prints exactly what each one reports so the
// verification harness that launched this (see docs/agent/ADR-LINUX-UT-STRATEGY.md's own
// "verification" section) can check real values rather than trusting a screenshot.
import QtQuick 2.15
import com.fylz.demo 1.0

Item {
    id: root

    // `refresh()` is called explicitly from `root`'s own `Component.onCompleted` below, in a
    // fixed order, rather than from each model's own `Component.onCompleted` handler: Qt
    // documents the relative firing order of separate objects' `Component.onCompleted` handlers
    // as UNDEFINED, and this screen's whole job is deterministic proof, not a race.
    FolderModel {
        id: folderModel
    }

    ArchiveModel {
        id: archiveModel
    }

    OperationQueue {
        id: operationQueue
    }

    PreviewProvider {
        id: previewProvider
    }

    RenameController {
        id: renameController
    }

    SearchController {
        id: searchController
    }

    // A visible label too, so a non-offscreen run (or a future screenshot-based check) has
    // something on screen beyond the console output every assertion actually reads.
    Column {
        anchors.fill: parent
        Text { text: "Fylz FFI-Qt bridge proof (M13.2/M13.3 minimal slice)" }
        Text { text: "folders: " + folderModel.count() + " rows, " + folderModel.totalBytes + " bytes" }
        Text { text: "archive: " + archiveModel.count() + " entries (" + archiveModel.formatName + ")" }
        Text { text: "queue: " + operationQueue.count() + " operations" }
    }

    Component.onCompleted: {
        console.log("FYLZ_DEMO_START")

        folderModel.refresh()
        archiveModel.refresh()
        operationQueue.refresh()

        console.log("FYLZ_FOLDER_ROWCOUNT " + folderModel.count())
        for (var i = 0; i < folderModel.count(); i++) {
            console.log("FYLZ_FOLDER_ROW " + i + " " + folderModel.rowSummary(i))
        }
        console.log("FYLZ_FOLDER_TOTALBYTES " + folderModel.totalBytes)

        console.log("FYLZ_ARCHIVE_ROWCOUNT " + archiveModel.count())
        for (var j = 0; j < archiveModel.count(); j++) {
            console.log("FYLZ_ARCHIVE_ROW " + j + " " + archiveModel.rowSummary(j))
        }
        console.log("FYLZ_ARCHIVE_FORMAT " + archiveModel.formatName)
        console.log("FYLZ_ARCHIVE_ERROR " + archiveModel.errorMessage)

        console.log("FYLZ_QUEUE_ROWCOUNT " + operationQueue.count())
        for (var k = 0; k < operationQueue.count(); k++) {
            console.log("FYLZ_QUEUE_ROW " + k + " " + operationQueue.rowSummary(k))
        }

        console.log("FYLZ_PREVIEW " + previewProvider.classify(previewProvider.samplePath))
        console.log("FYLZ_RENAME " + renameController.previewRename("photo.jpg", "{n}-edited"))
        console.log("FYLZ_RENAME_AVAILABLE " + renameController.available)
        console.log("FYLZ_SEARCH " + searchController.search("budget report"))
        console.log("FYLZ_SEARCH_AVAILABLE " + searchController.available)

        console.log("FYLZ_DEMO_END")

        // A `Timer` would need `import QtQml 2.15`'s own event-driven wait; `Qt.callLater` twice
        // is enough to let this frame's console output flush before the event loop is asked to
        // stop, without pulling in another import for a one-shot demo harness.
        Qt.callLater(function () { Qt.callLater(Qt.quit) })
    }
}
