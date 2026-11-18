//! The recycle bin: [`policy`] is `RecycleBinPolicy.kt`'s pure policy, ported one-to-one
//! (`RecycleBinPolicyTest.kt` is its parity oracle). [`trash`] is a from-scratch
//! freedesktop.org Trash specification implementation -- see its own doc comment for why the
//! Android `RecycleBinStore`/`RecycleBinService` pair is not this module's parity oracle at all.

pub mod policy;
// The freedesktop.org Trash backend is Unix-only (MetadataExt, PermissionsExt, getuid) and is
// meaningful only where that specification applies. Apple platforms get their own backend (macOS:
// Foundation's trashItem; iOS: no Trash exists, FZ-IOS5(c)), so it is not compiled there.
#[cfg(all(unix, not(target_vendor = "apple")))]
pub mod trash;
#[cfg(all(unix, not(target_vendor = "apple")))]
mod trashinfo;

pub use policy::allow_permanent_delete;
pub use policy::decide_default_delete;
pub use policy::DeleteDecision;
