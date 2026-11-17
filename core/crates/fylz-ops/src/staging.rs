//! Ported from `stagingName`/`isStagingName`/`STAGING_NAME_PREFIX` in
//! `app/src/main/java/io/github/mbaliga/fylz/operations/FileOperationService.kt` (P0.6):
//! every file (or top-level folder) write lands under this name first, regardless of conflict
//! policy, and reaches its requested name only after verification -- a process that dies
//! mid-write leaves a `.fylz-part-*` orphan, never a half-written file under the name the user
//! would see. `StagingNameTest.kt` is this module's parity oracle (`staging_tests.rs`).

/// Recognizes a staged write's name, wherever a caller needs to hide one from a listing or a
/// search -- must match what [`staging_name`] produces.
pub const STAGING_NAME_PREFIX: &str = ".fylz-part-";

/// `operation_id` and `item_index` make the name unique across concurrent and historical
/// operations without a filesystem round trip to check for collisions, and let recovery
/// identify which operation and item an orphan belongs to purely from its name if the journal
/// record itself is ever unreadable. Truncated to 255 UTF-8 bytes -- the limit most filesystems
/// this crate targets enforce per path component -- without splitting a multi-byte character.
pub fn staging_name(operation_id: &str, item_index: usize, requested_name: &str) -> String {
    let safe_name = requested_name.replace('/', "_");
    let full = format!("{STAGING_NAME_PREFIX}{operation_id}-{item_index}-{safe_name}");
    truncate_utf8_bytes(&full, 255)
}

/// True for any name [`staging_name`] could have produced -- a caller hiding staged writes from
/// a listing or a search only needs to check this, not reconstruct the exact name.
pub fn is_staging_name(name: &str) -> bool {
    name.starts_with(STAGING_NAME_PREFIX)
}

/// `s` truncated to at most `max_bytes` UTF-8 bytes, backing off to the nearest character
/// boundary at or below that limit rather than splitting a multi-byte character -- `str`'s
/// `is_char_boundary` is the direct equivalent of the Kotlin code's own continuation-byte check
/// (`(bytes[end].toInt() and 0xC0) == 0x80`).
fn truncate_utf8_bytes(s: &str, max_bytes: usize) -> String {
    if s.len() <= max_bytes {
        return s.to_string();
    }
    let mut end = max_bytes;
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    s[..end].to_string()
}

#[cfg(test)]
#[path = "staging_tests.rs"]
mod staging_tests;
