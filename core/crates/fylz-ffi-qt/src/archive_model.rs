//! `ArchiveModel`: a `QAbstractListModel` over one archive's contents, backed by
//! [`crate::logic::scan_archive`] -- a real header pass through `fylz-archive::inspect` (M3.3),
//! never a re-implementation of archive parsing. `fylz-archive` is finished (M3), so this
//! `QObject` is real, not a stub (`docs/agent/ADR-LINUX-UT-STRATEGY.md`).

#[cxx_qt::bridge]
pub mod qobject {
    unsafe extern "C++" {
        include!(<QtCore/QAbstractListModel>);
        type QAbstractListModel;
    }

    unsafe extern "C++" {
        include!("cxx-qt-lib/qstring.h");
        type QString = cxx_qt_lib::QString;

        include!("cxx-qt-lib/qhash.h");
        type QHash_i32_QByteArray = cxx_qt_lib::QHash<cxx_qt_lib::QHashPair_i32_QByteArray>;

        include!("cxx-qt-lib/qvariant.h");
        type QVariant = cxx_qt_lib::QVariant;

        include!("cxx-qt-lib/qmodelindex.h");
        type QModelIndex = cxx_qt_lib::QModelIndex;
    }

    #[qenum(ArchiveModel)]
    enum ArchiveRoles {
        Path,
        IsDirectory,
        SizeBytes,
    }

    unsafe extern "RustQt" {
        #[qobject]
        #[base = QAbstractListModel]
        #[qml_element]
        /// The archive file this model lists. Defaults to `FYLZ_DEMO_ARCHIVE` at construction
        /// (see `Default` below), matching `FolderModel::root_path`'s own reasoning.
        #[qproperty(QString, archive_path, cxx_name = "archivePath")]
        /// `Inspection::format_name` (e.g. `"ZIP"`) once `refresh` has run, or empty before that
        /// or on error.
        #[qproperty(QString, format_name, cxx_name = "formatName")]
        /// `fylz_archive::ArchiveError`'s message when `refresh` failed to open or inspect the
        /// archive; empty on success.
        #[qproperty(QString, error_message, cxx_name = "errorMessage")]
        type ArchiveModel = super::ArchiveModelRust;

        /// Re-reads `archivePath` via `fylz_archive::inspect` and resets the model.
        #[qinvokable]
        fn refresh(self: Pin<&mut ArchiveModel>);

        /// Demo-only convenience matching `FolderModel::row_summary`'s own reasoning.
        #[qinvokable]
        #[cxx_name = "rowSummary"]
        fn row_summary(self: &ArchiveModel, row: i32) -> QString;

        /// Demo-only convenience matching `FolderModel::count`'s own reasoning (a plain
        /// zero-argument row count QML script can call directly).
        #[qinvokable]
        fn count(self: &ArchiveModel) -> i32;
    }

    unsafe extern "RustQt" {
        #[inherit]
        #[cxx_name = "beginResetModel"]
        unsafe fn begin_reset_model(self: Pin<&mut ArchiveModel>);
        #[inherit]
        #[cxx_name = "endResetModel"]
        unsafe fn end_reset_model(self: Pin<&mut ArchiveModel>);
    }

    extern "RustQt" {
        #[qinvokable]
        #[cxx_override]
        #[cxx_name = "roleNames"]
        fn role_names(self: &ArchiveModel) -> QHash_i32_QByteArray;

        #[qinvokable]
        #[cxx_override]
        #[cxx_name = "rowCount"]
        fn row_count(self: &ArchiveModel, _parent: &QModelIndex) -> i32;

        #[qinvokable]
        #[cxx_override]
        fn data(self: &ArchiveModel, index: &QModelIndex, role: i32) -> QVariant;
    }
}

use core::pin::Pin;
use cxx_qt::CxxQtType;
use cxx_qt_lib::QByteArray;
use cxx_qt_lib::QHash;
use cxx_qt_lib::QHashPair_i32_QByteArray;
use cxx_qt_lib::QString;
use cxx_qt_lib::QVariant;
use std::path::PathBuf;

use crate::logic::scan_archive;
use crate::logic::ArchiveRow;

pub struct ArchiveModelRust {
    archive_path: QString,
    format_name: QString,
    error_message: QString,
    rows: Vec<ArchiveRow>,
}

impl Default for ArchiveModelRust {
    fn default() -> Self {
        let archive_path = std::env::var("FYLZ_DEMO_ARCHIVE").unwrap_or_default();
        Self {
            archive_path: QString::from(archive_path.as_str()),
            format_name: QString::default(),
            error_message: QString::default(),
            rows: Vec::new(),
        }
    }
}

impl qobject::ArchiveModel {
    pub fn refresh(mut self: Pin<&mut Self>) {
        let path = PathBuf::from(String::from(self.archive_path()));
        let scan = scan_archive(&path);
        let format_name = QString::from(scan.format_name.unwrap_or_default().as_str());
        let error_message = QString::from(scan.error.unwrap_or_default().as_str());
        // SAFETY: as `FolderModel::refresh` -- every row mutation happens strictly between the
        // inherited `beginResetModel`/`endResetModel` pair.
        unsafe {
            self.as_mut().begin_reset_model();
            self.as_mut().rust_mut().rows = scan.rows;
            self.as_mut().end_reset_model();
        }
        self.as_mut().set_format_name(format_name);
        self.as_mut().set_error_message(error_message);
    }

    pub fn row_summary(&self, row: i32) -> QString {
        match self.rows.get(usize::try_from(row).unwrap_or(usize::MAX)) {
            Some(row) => QString::from(
                format!(
                    "path={} isDirectory={} sizeBytes={}",
                    row.path, row.is_directory, row.size_bytes
                )
                .as_str(),
            ),
            None => QString::from(format!("<no such row {row}>").as_str()),
        }
    }

    pub fn role_names(&self) -> QHash<QHashPair_i32_QByteArray> {
        let mut roles = QHash::<QHashPair_i32_QByteArray>::default();
        roles.insert(qobject::ArchiveRoles::Path.repr, QByteArray::from("path"));
        roles.insert(
            qobject::ArchiveRoles::IsDirectory.repr,
            QByteArray::from("isDirectory"),
        );
        roles.insert(
            qobject::ArchiveRoles::SizeBytes.repr,
            QByteArray::from("sizeBytes"),
        );
        roles
    }

    pub fn row_count(&self, _parent: &qobject::QModelIndex) -> i32 {
        self.rows.len() as i32
    }

    pub fn count(&self) -> i32 {
        self.rows.len() as i32
    }

    pub fn data(&self, index: &qobject::QModelIndex, role: i32) -> QVariant {
        let Some(row) = self
            .rows
            .get(usize::try_from(index.row()).unwrap_or(usize::MAX))
        else {
            return QVariant::default();
        };
        match (qobject::ArchiveRoles { repr: role }) {
            qobject::ArchiveRoles::Path => QVariant::from(&QString::from(row.path.as_str())),
            qobject::ArchiveRoles::IsDirectory => QVariant::from(&row.is_directory),
            qobject::ArchiveRoles::SizeBytes => QVariant::from(&(row.size_bytes as i64)),
            _ => QVariant::default(),
        }
    }
}
