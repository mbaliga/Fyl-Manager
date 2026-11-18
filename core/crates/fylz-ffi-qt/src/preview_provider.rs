//! `PreviewProvider`: the master plan's M13.2 entry names this a `QQuickImageProvider` for
//! thumbnails. This crate builds the honest, documented partial stub described in
//! `docs/agent/ADR-LINUX-UT-STRATEGY.md` and `crate`'s own doc comment: real content
//! classification via `fylz-sniff` (M2.5, already done -- genuine magic-byte sniffing of the
//! file's own bytes, never a guess from its extension), but no actual pixel decode. A real
//! `QQuickImageProvider` is a plain (non-`QObject`) abstract C++ class, outside cxx-qt 0.7.3's
//! supported subclassing surface (`#[base = ...]` targets a `QObject`-derived base such as
//! `QAbstractListModel`, which `QQuickImageProvider` is not) -- wiring a hand-written C++ image
//! provider on top of this bridge is real, in-scope future work, not attempted here.

#[cxx_qt::bridge]
pub mod qobject {
    unsafe extern "C++" {
        include!("cxx-qt-lib/qstring.h");
        type QString = cxx_qt_lib::QString;
    }

    unsafe extern "RustQt" {
        #[qobject]
        #[qml_element]
        /// A sample file path for the demo screen to classify, defaulting to
        /// `FYLZ_DEMO_PREVIEW_PATH` at construction (matching `FolderModel::root_path`'s own
        /// reasoning) so `qml/main.qml` needs no context-property plumbing.
        #[qproperty(QString, sample_path, cxx_name = "samplePath")]
        type PreviewProvider = super::PreviewProviderRust;

        /// Real classification (`fylz-sniff`'s magic-byte detection of `path`'s own first bytes)
        /// plus an explicit, honest note that no thumbnail pixel is actually produced -- see this
        /// module's own doc comment for exactly why that half is not attempted here.
        #[qinvokable]
        fn classify(self: &PreviewProvider, path: &QString) -> QString;
    }
}

use cxx_qt_lib::QString;
use std::io::Read;

pub struct PreviewProviderRust {
    sample_path: QString,
}

impl Default for PreviewProviderRust {
    fn default() -> Self {
        let sample_path = std::env::var("FYLZ_DEMO_PREVIEW_PATH").unwrap_or_default();
        Self {
            sample_path: QString::from(sample_path.as_str()),
        }
    }
}

impl qobject::PreviewProvider {
    pub fn classify(&self, path: &QString) -> QString {
        let path = String::from(path);
        let mut header = vec![0u8; fylz_sniff::HEADER_LEN];
        let read = match std::fs::File::open(&path).and_then(|mut file| {
            let mut total = 0usize;
            loop {
                match file.read(&mut header[total..]) {
                    Ok(0) => break Ok(total),
                    Ok(n) => total += n,
                    Err(error) => break Err(error),
                }
            }
        }) {
            Ok(read) => read,
            Err(error) => return QString::from(format!("error: {path}: {error}").as_str()),
        };

        match fylz_sniff::sniff(&header[..read]) {
            Some(format) => QString::from(
                format!(
                    "{} ({}) -- classified via fylz-sniff; NOT_IMPLEMENTED: pixel thumbnail \
                     rendering (see PreviewProvider's own doc comment)",
                    format.label(),
                    format.mime().unwrap_or("unknown mime")
                )
                .as_str(),
            ),
            None => QString::from(
                "unrecognized: fylz-sniff did not match any known format for this file's header",
            ),
        }
    }
}
