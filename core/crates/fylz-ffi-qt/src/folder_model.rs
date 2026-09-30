//! `FolderModel`: a `QAbstractListModel` over a real directory, backed by
//! [`crate::logic::scan_folder`] -- see that function's own doc comment for exactly which
//! `fylz-ops` policies back each column. This is the minimal proof-of-bridge slice
//! (`docs/agent/ADR-LINUX-UT-STRATEGY.md`), not the full dual-pane browser
//! `docs/agent/MASTER_PLAN.md` M13.3 describes for the real UI.

#[cxx_qt::bridge]
pub mod qobject {
    unsafe extern "C++" {
        include!(<QtCore/QAbstractListModel>);
        /// A flat listing, not a tree: the minimal slice this crate builds does not need
        /// `QAbstractItemModel`'s parent/child machinery.
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

    /// The columns a QML delegate binds to via `model.<role>`.
    #[qenum(FolderModel)]
    enum FolderRoles {
        Name,
        IsDirectory,
        SizeBytes,
        /// `fylz_ops::staging::is_staging_name` -- a write in progress, real policy output.
        IsStaging,
        /// A real `fylz_ops::preflight::PreflightPolicy` case-insensitive `NameCollision`
        /// verdict, not a hand-rolled duplicate check.
        HasConflict,
    }

    unsafe extern "RustQt" {
        #[qobject]
        #[base = QAbstractListModel]
        #[qml_element]
        /// The directory this model lists. Defaults to `FYLZ_DEMO_FOLDER` at construction (see
        /// `Default` below) so the minimal proof screen (`qml/main.qml`) needs no
        /// context-property plumbing -- cxx-qt-lib 0.7.3's `QQmlApplicationEngine` binding
        /// exposes no `setContextProperty` to do that with anyway.
        #[qproperty(QString, root_path, cxx_name = "rootPath")]
        /// The sum of every top-level entry's real size (`fylz_ops::preflight`'s own recursive
        /// directory walk) -- the same number a real preflight free-space check would use.
        #[qproperty(i64, total_bytes, cxx_name = "totalBytes")]
        type FolderModel = super::FolderModelRust;

        /// Re-scans `rootPath` from disk and resets the model. Called once from QML's
        /// `Component.onCompleted`; this minimal slice has no filesystem watcher (M13.4).
        #[qinvokable]
        fn refresh(self: Pin<&mut FolderModel>);

        /// One row's fields as a single pipe-separated string, for the verification harness
        /// (`qml/main.qml`) to `console.log` without hand-building a `QModelIndex` per field.
        /// `data()`/`roleNames()`/`rowCount()` below are the real `QAbstractListModel` contract a
        /// `ListView` delegate would actually bind against; this is a demo-only convenience on
        /// top of it, not a replacement for it.
        #[qinvokable]
        #[cxx_name = "rowSummary"]
        fn row_summary(self: &FolderModel, row: i32) -> QString;

        /// A plain zero-argument row count, for QML script to call directly.
        /// `rowCount(parent)` below is the real, required `QAbstractListModel` override (a
        /// `ListView` delegate calls it as Qt's own C++ virtual dispatch, which never goes
        /// through QML's argument-count checking) -- calling it BY NAME from QML script instead
        /// hits `QModelIndex`'s C++-side default argument, which an overridden method does not
        /// inherit through Qt's meta-object system, and fails with "Insufficient arguments".
        /// This sidesteps that without changing the real interface at all.
        #[qinvokable]
        fn count(self: &FolderModel) -> i32;
    }

    unsafe extern "RustQt" {
        #[inherit]
        #[cxx_name = "beginResetModel"]
        unsafe fn begin_reset_model(self: Pin<&mut FolderModel>);
        #[inherit]
        #[cxx_name = "endResetModel"]
        unsafe fn end_reset_model(self: Pin<&mut FolderModel>);
    }

    extern "RustQt" {
        #[qinvokable]
        #[cxx_override]
        #[cxx_name = "roleNames"]
        fn role_names(self: &FolderModel) -> QHash_i32_QByteArray;

        #[qinvokable]
        #[cxx_override]
        #[cxx_name = "rowCount"]
        fn row_count(self: &FolderModel, _parent: &QModelIndex) -> i32;

        #[qinvokable]
        #[cxx_override]
        fn data(self: &FolderModel, index: &QModelIndex, role: i32) -> QVariant;
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

use crate::logic::scan_folder;
use crate::logic::FolderRow;

pub struct FolderModelRust {
    root_path: QString,
    total_bytes: i64,
    rows: Vec<FolderRow>,
}

impl Default for FolderModelRust {
    fn default() -> Self {
        let root_path = std::env::var("FYLZ_DEMO_FOLDER").unwrap_or_default();
        Self {
            root_path: QString::from(root_path.as_str()),
            total_bytes: 0,
            rows: Vec::new(),
        }
    }
}

impl qobject::FolderModel {
    pub fn refresh(mut self: Pin<&mut Self>) {
        let path = PathBuf::from(String::from(self.root_path()));
        let scan = scan_folder(&path);
        let total_bytes = scan.total_bytes as i64;
        // SAFETY: `begin_reset_model`/`end_reset_model` are the inherited
        // `QAbstractItemModel::beginResetModel`/`endResetModel`; every row mutation happens
        // strictly between the two calls, exactly as Qt's own subclassing contract requires.
        unsafe {
            self.as_mut().begin_reset_model();
            self.as_mut().rust_mut().rows = scan.rows;
            self.as_mut().end_reset_model();
        }
        self.as_mut().set_total_bytes(total_bytes);
    }

    pub fn row_summary(&self, row: i32) -> QString {
        match self.rows.get(usize::try_from(row).unwrap_or(usize::MAX)) {
            Some(row) => QString::from(
                format!(
                    "name={} isDirectory={} sizeBytes={} isStaging={} hasConflict={}",
                    row.name,
                    row.is_directory,
                    row.size_bytes,
                    row.is_staging,
                    row.has_name_collision
                )
                .as_str(),
            ),
            None => QString::from(format!("<no such row {row}>").as_str()),
        }
    }

    pub fn role_names(&self) -> QHash<QHashPair_i32_QByteArray> {
        let mut roles = QHash::<QHashPair_i32_QByteArray>::default();
        roles.insert(qobject::FolderRoles::Name.repr, QByteArray::from("name"));
        roles.insert(
            qobject::FolderRoles::IsDirectory.repr,
            QByteArray::from("isDirectory"),
        );
        roles.insert(
            qobject::FolderRoles::SizeBytes.repr,
            QByteArray::from("sizeBytes"),
        );
        roles.insert(
            qobject::FolderRoles::IsStaging.repr,
            QByteArray::from("isStaging"),
        );
        roles.insert(
            qobject::FolderRoles::HasConflict.repr,
            QByteArray::from("hasConflict"),
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
        match (qobject::FolderRoles { repr: role }) {
            qobject::FolderRoles::Name => QVariant::from(&QString::from(row.name.as_str())),
            qobject::FolderRoles::IsDirectory => QVariant::from(&row.is_directory),
            qobject::FolderRoles::SizeBytes => QVariant::from(&(row.size_bytes as i64)),
            qobject::FolderRoles::IsStaging => QVariant::from(&row.is_staging),
            qobject::FolderRoles::HasConflict => QVariant::from(&row.has_name_collision),
            _ => QVariant::default(),
        }
    }
}
