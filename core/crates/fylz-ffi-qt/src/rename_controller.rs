//! `RenameController`: an honest stub. `fylz-rename` (`docs/agent/MASTER_PLAN.md` M7) is still a
//! one-line stub crate with no rule engine at all (`docs/agent/ADR-LINUX-UT-STRATEGY.md` §1), so
//! there is nothing real for this `QObject` to call. Its shape (the property and invokable names
//! QML code can already compile against) is real; every actual operation returns a clearly
//! labelled "not implemented yet" string rather than inventing rename behaviour.

#[cxx_qt::bridge]
pub mod qobject {
    unsafe extern "C++" {
        include!("cxx-qt-lib/qstring.h");
        type QString = cxx_qt_lib::QString;
    }

    unsafe extern "RustQt" {
        #[qobject]
        #[qml_element]
        /// Always `false`: `fylz-rename` has no engine behind this controller yet. A real M7
        /// implementation would flip this once it exists, letting QML gate its own "Rename"
        /// affordance on it rather than on a version check.
        #[qproperty(bool, available)]
        type RenameController = super::RenameControllerRust;

        /// Always returns a "not implemented yet" message naming `fylz-rename` and M7, never a
        /// fabricated preview.
        #[qinvokable]
        #[cxx_name = "previewRename"]
        fn preview_rename(
            self: &RenameController,
            source_name: &QString,
            pattern: &QString,
        ) -> QString;
    }
}

use cxx_qt_lib::QString;

#[derive(Default)]
pub struct RenameControllerRust {
    available: bool,
}

impl qobject::RenameController {
    pub fn preview_rename(&self, source_name: &QString, pattern: &QString) -> QString {
        QString::from(
            format!(
                "NOT_IMPLEMENTED: fylz-rename (M7) is still a one-line stub crate with no rule \
                 engine; cannot preview renaming {:?} with pattern {:?}.",
                String::from(source_name),
                String::from(pattern)
            )
            .as_str(),
        )
    }
}
