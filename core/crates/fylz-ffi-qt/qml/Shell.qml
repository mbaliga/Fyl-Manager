// The Fylz app shell for Ubuntu Touch and desktop Linux (docs/design/ubuntu-touch-port.md).
//
// Deliberately MINIMAL and honest: a folder browser (open folders, Up), an archive viewer for
// files that look like archives, and an operation-journal count. It is NOT the rooms IA, edge
// scrubber, Quick Look or Hyle theme the master plan describes; those remain future work. Plain
// QtQuick only (no Lomiri Components import) so it loads on any Qt 5.15 install, including CI.
//
// Every list is bound to a REAL cxx-qt model from the com.fylz.demo module (the module URI is a
// leftover of the M13.2 proof harness and is kept so the bridge sources do not churn).
//
// Smoke mode: launched with `--smoke`, the shell logs what it shows (FYLZ_SHELL_* lines) and
// quits, so a CI lane can assert on real values under QT_QPA_PLATFORM=offscreen.
import QtQuick 2.15
import com.fylz.demo 1.0

Item {
    id: root
    width: 360
    height: 720

    readonly property bool smoke: Qt.application.arguments.indexOf("--smoke") >= 0
    // Touch-target unit: 48 logical px is the minimum comfortable target on a phone.
    readonly property int target: 48
    property string view: "folder"   // "folder" | "archive"

    FolderModel { id: folderModel }
    ArchiveModel { id: archiveModel }
    OperationQueue { id: operationQueue }

    function openFolder(path) {
        folderModel.rootPath = path
        folderModel.refresh()
    }

    function openEntry(name, isDirectory) {
        var path = folderModel.childPath(name)
        if (isDirectory) {
            openFolder(path)
        } else if (folderModel.isArchiveName(name)) {
            archiveModel.archivePath = path
            archiveModel.refresh()
            root.view = "archive"
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#ffffff"
    }

    // ---- header ---------------------------------------------------------------------------
    Rectangle {
        id: header
        anchors { top: parent.top; left: parent.left; right: parent.right }
        height: root.target + 8
        color: "#eeeeee"

        Rectangle {
            id: navButton
            objectName: "navButton"
            anchors { left: parent.left; verticalCenter: parent.verticalCenter; leftMargin: 8 }
            width: root.target * 1.5
            height: root.target
            color: "#cccccc"
            border.color: "#333333"
            Text {
                anchors.centerIn: parent
                text: root.view === "folder" ? "↑ Up" : "← Back"
                font.pixelSize: 16
            }
            MouseArea {
                anchors.fill: parent
                onClicked: {
                    if (root.view === "archive") {
                        root.view = "folder"
                    } else {
                        root.openFolder(folderModel.parentPath())
                    }
                }
            }
        }

        Text {
            id: titleText
            anchors {
                left: navButton.right; right: parent.right
                verticalCenter: parent.verticalCenter
                leftMargin: 12; rightMargin: 8
            }
            elide: Text.ElideLeft
            font.pixelSize: 16
            text: root.view === "folder"
                  ? folderModel.rootPath
                  : archiveModel.archivePath + "  [" + archiveModel.formatName + "]"
        }
    }

    // ---- folder view ----------------------------------------------------------------------
    ListView {
        id: folderList
        objectName: "folderList"
        visible: root.view === "folder"
        anchors { top: header.bottom; bottom: footer.top; left: parent.left; right: parent.right }
        clip: true
        model: folderModel
        delegate: Rectangle {
            width: folderList.width
            height: root.target + 8
            color: index % 2 === 0 ? "#ffffff" : "#f6f6f6"
            Text {
                anchors { left: parent.left; leftMargin: 12; verticalCenter: parent.verticalCenter }
                width: parent.width - 120
                elide: Text.ElideMiddle
                font.pixelSize: 16
                text: (isDirectory ? "[folder]  " : "") + name
                      + (isStaging ? "  (write in progress)" : "")
                      + (hasConflict ? "  (name clash)" : "")
            }
            Text {
                anchors { right: parent.right; rightMargin: 12; verticalCenter: parent.verticalCenter }
                font.pixelSize: 14
                text: isDirectory ? "" : folderModel.humanSize(sizeBytes)
            }
            MouseArea {
                anchors.fill: parent
                onClicked: root.openEntry(name, isDirectory)
            }
        }
        Text {
            anchors.centerIn: parent
            visible: folderList.count === 0
            font.pixelSize: 16
            text: "This folder is empty or cannot be read."
        }
    }

    // ---- archive view ---------------------------------------------------------------------
    ListView {
        id: archiveList
        objectName: "archiveList"
        visible: root.view === "archive"
        anchors { top: header.bottom; bottom: footer.top; left: parent.left; right: parent.right }
        clip: true
        model: archiveModel
        delegate: Rectangle {
            width: archiveList.width
            height: root.target
            color: index % 2 === 0 ? "#ffffff" : "#f6f6f6"
            Text {
                anchors { left: parent.left; leftMargin: 12; verticalCenter: parent.verticalCenter }
                width: parent.width - 120
                elide: Text.ElideMiddle
                font.pixelSize: 15
                text: (isDirectory ? "[folder]  " : "") + path
            }
            Text {
                anchors { right: parent.right; rightMargin: 12; verticalCenter: parent.verticalCenter }
                font.pixelSize: 14
                text: isDirectory ? "" : folderModel.humanSize(sizeBytes)
            }
        }
        Text {
            anchors { centerIn: parent }
            width: parent.width - 32
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            visible: archiveList.count === 0
            font.pixelSize: 16
            text: archiveModel.errorMessage !== ""
                  ? "Cannot open this archive: " + archiveModel.errorMessage
                  : "This archive has no entries."
        }
    }

    // ---- footer ---------------------------------------------------------------------------
    Rectangle {
        id: footer
        anchors { bottom: parent.bottom; left: parent.left; right: parent.right }
        height: 32
        color: "#eeeeee"
        Text {
            anchors { left: parent.left; leftMargin: 12; verticalCenter: parent.verticalCenter }
            font.pixelSize: 13
            text: root.view === "folder"
                  ? folderModel.count() + " items, " + folderModel.humanSize(folderModel.totalBytes)
                    + "  |  journal: " + operationQueue.count() + " operations"
                  : archiveModel.count() + " entries  |  read-only view"
        }
    }

    Component.onCompleted: {
        // Deterministic order (Qt leaves separate objects' onCompleted order undefined).
        folderModel.refresh()
        operationQueue.refresh()
        if (root.smoke) {
            console.log("FYLZ_SHELL_START")
            console.log("FYLZ_SHELL_PATH " + folderModel.rootPath)
            console.log("FYLZ_SHELL_ROWS " + folderModel.count())
            for (var i = 0; i < folderModel.count(); i++) {
                console.log("FYLZ_SHELL_ROW " + i + " " + folderModel.rowSummary(i))
            }
            // Drive the same code paths a tap would, then report.
            var archiveName = ""
            var folderName = ""
            for (var j = 0; j < folderModel.count(); j++) {
                var summary = folderModel.rowSummary(j)
                var m = /name=(.*) isDirectory=(true|false)/.exec(summary)
                if (m && m[2] === "true" && folderName === "") folderName = m[1]
                if (m && m[2] === "false" && folderModel.isArchiveName(m[1]) && archiveName === "")
                    archiveName = m[1]
            }
            if (archiveName !== "") {
                root.openEntry(archiveName, false)
                console.log("FYLZ_SHELL_VIEW " + root.view)
                console.log("FYLZ_SHELL_ARCHIVE_FORMAT " + archiveModel.formatName)
                console.log("FYLZ_SHELL_ARCHIVE_ROWS " + archiveModel.count())
                root.view = "folder"
            }
            if (folderName !== "") {
                root.openEntry(folderName, true)
                console.log("FYLZ_SHELL_DESCENDED " + folderModel.rootPath)
                root.openFolder(folderModel.parentPath())
                console.log("FYLZ_SHELL_ASCENDED " + folderModel.rootPath)
            }
            console.log("FYLZ_SHELL_END")
            Qt.callLater(function () { Qt.callLater(Qt.quit) })
        }
    }
}
