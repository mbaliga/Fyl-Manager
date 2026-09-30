//! `SearchController`: an honest stub. `fylz-query` and `fylz-index` (`docs/agent/
//! MASTER_PLAN.md` M8) are still one-line stub crates -- no query AST, no index schema, no FTS or
//! vector ranking exist yet (`docs/agent/ADR-LINUX-UT-STRATEGY.md` §1), so there is nothing real
//! for this `QObject` to call. Its shape is real; every actual search returns a clearly labelled
//! "not implemented yet" string rather than inventing search results.

#[cxx_qt::bridge]
pub mod qobject {
    unsafe extern "C++" {
        include!("cxx-qt-lib/qstring.h");
        type QString = cxx_qt_lib::QString;
    }

    unsafe extern "RustQt" {
        #[qobject]
        #[qml_element]
        /// Always `false`: neither `fylz-query` nor `fylz-index` has an engine behind this
        /// controller yet.
        #[qproperty(bool, available)]
        type SearchController = super::SearchControllerRust;

        /// Always returns a "not implemented yet" message naming `fylz-query`/`fylz-index` and
        /// M8, never a fabricated result list.
        #[qinvokable]
        fn search(self: &SearchController, query: &QString) -> QString;
    }
}

use cxx_qt_lib::QString;

#[derive(Default)]
pub struct SearchControllerRust {
    available: bool,
}

impl qobject::SearchController {
    pub fn search(&self, query: &QString) -> QString {
        QString::from(
            format!(
                "NOT_IMPLEMENTED: fylz-query/fylz-index (M8) are still one-line stub crates with \
                 no query engine or index; cannot search for {:?}.",
                String::from(query)
            )
            .as_str(),
        )
    }
}
