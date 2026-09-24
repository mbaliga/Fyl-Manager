//! Uniffi bindings exposed to Kotlin; implementation lands in M2.2.
//!
//! This is the M2.2 FFI skeleton: two trivial exports proving the whole pipeline
//! (Rust -> uniffi -> generated Kotlin -> Gradle) end to end. Real content sniffing
//! is `fylz-sniff`'s job (M2.5) and will replace `sniff`'s stub body.

uniffi::setup_scaffolding!();

/// The `fylz-core` workspace version, for the Kotlin side to log/display.
#[uniffi::export]
pub fn fylz_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Stub for M2.5's real content-sniffing entry point. `path_fd` is an already-open,
/// caller-owned file descriptor (never a path string, so this never touches SAF
/// permission checks itself); the stub neither reads nor closes it.
#[uniffi::export]
pub async fn sniff(path_fd: i32) -> String {
    let _ = path_fd;
    "stub: fylz-sniff lands in M2.5".to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_matches_the_crate_manifest() {
        assert_eq!(fylz_version(), env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn sniff_stub_ignores_the_fd_and_never_blocks() {
        assert_eq!(
            pollster::block_on(sniff(-1)),
            "stub: fylz-sniff lands in M2.5"
        );
    }
}
