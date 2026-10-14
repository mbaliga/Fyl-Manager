//! The recycle bin: [`policy`] is `RecycleBinPolicy.kt`'s pure policy, ported one-to-one
//! (`RecycleBinPolicyTest.kt` is its parity oracle). [`trash`] is a from-scratch
//! freedesktop.org Trash specification implementation -- see its own doc comment for why the
//! Android `RecycleBinStore`/`RecycleBinService` pair is not this module's parity oracle at all.

pub mod policy;
pub mod trash;
mod trashinfo;

pub use policy::allow_permanent_delete;
pub use policy::decide_default_delete;
pub use policy::DeleteDecision;
