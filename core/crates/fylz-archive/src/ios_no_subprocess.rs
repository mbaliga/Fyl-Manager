//! iOS only: the two subprocess helpers libarchive's external-program filters call, defined here
//! as "cannot start a child".
//!
//! `build.rs` forces libarchive's fork/vfork/posix_spawn probes to "absent" for iOS (there is no
//! subprocess there), which makes `filter_fork_posix.c` compile to an empty object. The callers
//! (`archive_read_support_filter_program.c`, `archive_write_add_filter_program.c`) are still
//! compiled unconditionally and still reference `__archive_create_child` and
//! `__archive_check_child`, so without these definitions the app's final link fails with
//! "Undefined symbols ... ___archive_create_child". A static library never links, so only the
//! Xcode link step sees it.
//!
//! `__archive_create_child` reports failure, which libarchive turns into its ordinary "unable to
//! run program" error; `__archive_check_child` is then unreachable (it is only called once a
//! child exists) and does nothing.

use std::os::raw::c_char;
use std::os::raw::c_int;

/// libarchive's `ARCHIVE_FAILED` (`archive.h`).
const ARCHIVE_FAILED: c_int = -25;

/// `int __archive_create_child(const char *cmd, int *child_stdin, int *child_stdout,
/// pid_t *out_child)` from libarchive's `filter_fork.h`. `pid_t` is a 32-bit int on Apple
/// platforms. Never starts anything and never writes through its pointers.
#[no_mangle]
pub extern "C" fn __archive_create_child(
    _cmd: *const c_char,
    _child_stdin: *mut c_int,
    _child_stdout: *mut c_int,
    _out_child: *mut i32,
) -> c_int {
    ARCHIVE_FAILED
}

/// `void __archive_check_child(int in, int out)` from libarchive's `filter_fork.h`.
#[no_mangle]
pub extern "C" fn __archive_check_child(_stdin: c_int, _stdout: c_int) {}
