//! `OperationQueue`: a `QAbstractListModel` view of a real `fylz_ops::journal::Journal`, backed
//! by [`crate::logic::list_operations`]. Nothing here performs an actual file copy/move (that is
//! a later dispatch's work); what M13.2 delivers is wiring the queue/journal *state* through to
//! QML for real, including `Journal::open`'s own reconcile-after-process-death sweep -- see
//! `src/bin/seed_demo.rs` and `logic.rs`'s own tests for how the end-to-end demo exercises that
//! for real rather than merely asserting it.

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

    #[qenum(OperationQueue)]
    enum QueueRoles {
        OperationId,
        Kind,
        State,
        ItemCount,
        ProgressPercent,
    }

    unsafe extern "RustQt" {
        #[qobject]
        #[base = QAbstractListModel]
        #[qml_element]
        /// The journal file this queue lists. Defaults to `FYLZ_DEMO_JOURNAL` at construction,
        /// matching `FolderModel::root_path`'s own reasoning.
        #[qproperty(QString, journal_path, cxx_name = "journalPath")]
        type OperationQueue = super::OperationQueueRust;

        /// Re-opens `journalPath` (via `fylz_ops::journal::Journal::open`, running its real
        /// reconcile sweep whenever this is the first open of this path in this process) and
        /// resets the model.
        #[qinvokable]
        fn refresh(self: Pin<&mut OperationQueue>);

        /// Demo-only convenience matching `FolderModel::row_summary`'s own reasoning.
        #[qinvokable]
        #[cxx_name = "rowSummary"]
        fn row_summary(self: &OperationQueue, row: i32) -> QString;

        /// Demo-only convenience matching `FolderModel::count`'s own reasoning (a plain
        /// zero-argument row count QML script can call directly).
        #[qinvokable]
        fn count(self: &OperationQueue) -> i32;
    }

    unsafe extern "RustQt" {
        #[inherit]
        #[cxx_name = "beginResetModel"]
        unsafe fn begin_reset_model(self: Pin<&mut OperationQueue>);
        #[inherit]
        #[cxx_name = "endResetModel"]
        unsafe fn end_reset_model(self: Pin<&mut OperationQueue>);
    }

    extern "RustQt" {
        #[qinvokable]
        #[cxx_override]
        #[cxx_name = "roleNames"]
        fn role_names(self: &OperationQueue) -> QHash_i32_QByteArray;

        #[qinvokable]
        #[cxx_override]
        #[cxx_name = "rowCount"]
        fn row_count(self: &OperationQueue, _parent: &QModelIndex) -> i32;

        #[qinvokable]
        #[cxx_override]
        fn data(self: &OperationQueue, index: &QModelIndex, role: i32) -> QVariant;
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

use crate::logic::list_operations;
use crate::logic::OperationRow;

pub struct OperationQueueRust {
    journal_path: QString,
    rows: Vec<OperationRow>,
}

impl Default for OperationQueueRust {
    fn default() -> Self {
        let journal_path = std::env::var("FYLZ_DEMO_JOURNAL").unwrap_or_default();
        Self {
            journal_path: QString::from(journal_path.as_str()),
            rows: Vec::new(),
        }
    }
}

impl qobject::OperationQueue {
    pub fn refresh(mut self: Pin<&mut Self>) {
        let path = PathBuf::from(String::from(self.journal_path()));
        let rows = list_operations(&path);
        // SAFETY: as `FolderModel::refresh` -- every row mutation happens strictly between the
        // inherited `beginResetModel`/`endResetModel` pair.
        unsafe {
            self.as_mut().begin_reset_model();
            self.as_mut().rust_mut().rows = rows;
            self.as_mut().end_reset_model();
        }
    }

    pub fn row_summary(&self, row: i32) -> QString {
        match self.rows.get(usize::try_from(row).unwrap_or(usize::MAX)) {
            Some(row) => QString::from(
                format!(
                    "id={} kind={} state={} itemCount={} progressPercent={}",
                    row.id, row.kind, row.state, row.item_count, row.progress_percent
                )
                .as_str(),
            ),
            None => QString::from(format!("<no such row {row}>").as_str()),
        }
    }

    pub fn role_names(&self) -> QHash<QHashPair_i32_QByteArray> {
        let mut roles = QHash::<QHashPair_i32_QByteArray>::default();
        roles.insert(
            qobject::QueueRoles::OperationId.repr,
            QByteArray::from("operationId"),
        );
        roles.insert(qobject::QueueRoles::Kind.repr, QByteArray::from("kind"));
        roles.insert(qobject::QueueRoles::State.repr, QByteArray::from("state"));
        roles.insert(
            qobject::QueueRoles::ItemCount.repr,
            QByteArray::from("itemCount"),
        );
        roles.insert(
            qobject::QueueRoles::ProgressPercent.repr,
            QByteArray::from("progressPercent"),
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
        match (qobject::QueueRoles { repr: role }) {
            qobject::QueueRoles::OperationId => QVariant::from(&QString::from(row.id.as_str())),
            qobject::QueueRoles::Kind => QVariant::from(&QString::from(row.kind.as_str())),
            qobject::QueueRoles::State => QVariant::from(&QString::from(row.state.as_str())),
            qobject::QueueRoles::ItemCount => QVariant::from(&(row.item_count as i64)),
            qobject::QueueRoles::ProgressPercent => QVariant::from(&row.progress_percent),
            _ => QVariant::default(),
        }
    }
}
