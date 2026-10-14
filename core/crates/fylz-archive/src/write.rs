//! Archive write engine over libarchive's write side (M3.5a, `docs/agent/DESIGN-M35-CREATE.md`
//! section 2.5). The engine never sees a Uri, a document, or a password: it is handed two
//! caller-owned file descriptors -- `in_fd` carries the `FZW1`-framed manifest and source bytes,
//! `out_fd` is the raw archive stream -- and a small set of typed options, and it either produces
//! `WriteReport { entries, bytes_in, bytes_out }` or an [ArchiveError]. Everything about *why* a
//! source is included, what it is named, or how the output is split into parts is the caller's
//! business (`operations/ArchiveCreator.kt`); this module only turns frames into libarchive calls.
//!
//! **The locale pin** ([LocalePin]) is the fix the design's rev-2 review made a blocker over: with
//! `ENABLE_ICONV` off (`build.rs`), libarchive's UTF-8 charset conversion for a header name is a
//! no-op *only when the calling thread's own `nl_langinfo(CODESET)` already reports UTF-8* -- and
//! the C locale every fresh thread starts in (a plain `cargo test` binary, a freshly spawned
//! `:decoders:write` thread) does not. Without this pin, `hdrcharset=UTF-8` and any non-ASCII
//! pathname at all would fail the header outright, in every locale, not merely come out as
//! mojibake (confirmed against the host build in `write_tests.rs`, on a thread the test itself
//! never touches the locale of).
//!
//! **Poisoning** ([Writer::abort]) is the other blocker: `archive_write_free` calls
//! `archive_write_close` if the writer was never explicitly closed, and `archive_write_close` for
//! a ZIP/tar writer always emits a well-formed trailer or central directory -- a truncated write
//! and a clean one look the same to `free` unless something makes the writer's own next callback
//! fail. [WriteContext::poisoned] is that something: once set, the `open2` write callback returns
//! `-1` regardless of what `close` tries to flush, so `abort`'s "free without closing" genuinely
//! produces an archive that does not parse (`write_tests.rs` proves it, not just asserts it).
//!
//! **Frame protocol** (`FZW1`, all integers little-endian):
//!
//! ```text
//! MAGIC  "FZW1"
//! ENTRY  0x01 ordinal:u32 kind:u8(1 file,2 dir) size:i64(-1 unknown) mtime:i64(epoch ms; 0 -> "now", clamped to a 32-bit time_t) mode:u32(masked to 0o7777) path_len:u32(<=65536, including a directory's trailing slash) utf8_path
//! DATA   0x02 ordinal:u32 len:u32(1..=1 MiB) bytes                          -- only between a file ENTRY and its END; a directory ENTRY takes neither
//! END    0x03 ordinal:u32 bytes:u64                                        -- bytes MUST equal the sum of that ordinal's DATA lengths
//! FINISH 0x04                                                              -- only when no entry is open; closes the archive normally
//! ABORT  0x05 msg_len:u16 msg                                              -- only when no entry is open; the engine returns Cancelled and poisons the writer before closing
//! ```
//!
//! `size` is this module's one addition to the design's own grammar box, which describes the
//! unknown-size rule in prose ("unknown size sends no size field at all") without spelling out
//! where that field lives; it is placed exactly where [crate::listing]'s sibling `FZX1` codec
//! (`fylz-ffi-android`'s `frames::TAG_BEGIN`) puts its own `declared:i64(-1 unknown)` field, for
//! the same reason and the same sentinel -- recorded as a design gap filled, not a deviation from
//! anything the design actually specified. Ordinals strictly increasing; a directory `ENTRY` is
//! header-and-`finish_entry`d immediately, with no `open` state and therefore no `DATA`/`END` of
//! its own to violate. Anything else the grammar forbids (`DATA`/`END` with no open `ENTRY`,
//! `FINISH`/`ABORT` while one is open, an `END` whose count disagrees with what was sent, bytes
//! past `FINISH`, a non-increasing ordinal, a size the feeder never declared but the entry is
//! `tar.*`) is a **protocol violation**: `ArchiveError::Failed`, never a panic, and -- since M3.5
//! has no partial-success create -- it poisons and fails the *whole* write, not just that entry
//! (design section 2.3 step 4's own recorded choice). A byte count that would push an entry past
//! its own declared size is the one case worth stopping the whole write for (a shortfall is not:
//! ZIP's data descriptor records the true count, and a `tar.*` source of unknown size is spooled
//! to a known one before its `ENTRY` frame is ever sent -- the caller's job, not this module's).

use crate::policy;
use crate::ArchiveError;
use std::cell::Cell;
use std::ffi::CString;
use std::io::Read;
use std::io::Write;
use std::os::raw::c_int;
use std::os::raw::c_void;
use std::os::unix::io::RawFd;

mod sys {
    //! Write-side externs, hand-written exactly like the read-side `sys` module in `lib.rs` (see
    //! its own doc comment for why): every declaration here was checked against
    //! `core/third_party/libarchive/libarchive/archive.h`/`archive_entry.h` at v3.8.9, plus the
    //! platform `<locale.h>`/`<langinfo.h>` the locale pin needs (checked against both the Android
    //! NDK's bionic headers and this workspace's host glibc, since `write_frames_io` runs under
    //! both: cross-compiled into `:decoders:write`, and natively under `cargo test`).
    use std::os::raw::c_char;
    use std::os::raw::c_int;
    use std::os::raw::c_void;

    pub use crate::sys::Archive;
    pub use crate::sys::LaInt64T;
    pub use crate::sys::LaSsizeT;
    pub use crate::sys::Mode;
    pub use crate::sys::TimeT;

    #[repr(C)]
    pub struct ArchiveEntry {
        _private: [u8; 0],
    }

    pub type OpenCallback = unsafe extern "C" fn(*mut Archive, *mut c_void) -> c_int;
    pub type WriteCallback =
        unsafe extern "C" fn(*mut Archive, *mut c_void, *const c_void, usize) -> LaSsizeT;
    pub type CloseCallback = unsafe extern "C" fn(*mut Archive, *mut c_void) -> c_int;
    pub type FreeCallback = unsafe extern "C" fn(*mut Archive, *mut c_void) -> c_int;

    unsafe extern "C" {
        pub fn archive_write_new() -> *mut Archive;
        pub fn archive_write_free(a: *mut Archive) -> c_int;
        pub fn archive_write_close(a: *mut Archive) -> c_int;
        pub fn archive_write_set_bytes_per_block(a: *mut Archive, bytes_per_block: c_int) -> c_int;
        pub fn archive_write_set_options(a: *mut Archive, options: *const c_char) -> c_int;
        pub fn archive_write_set_format_zip(a: *mut Archive) -> c_int;
        pub fn archive_write_set_format_pax_restricted(a: *mut Archive) -> c_int;
        pub fn archive_write_add_filter_gzip(a: *mut Archive) -> c_int;
        pub fn archive_write_add_filter_xz(a: *mut Archive) -> c_int;
        pub fn archive_write_add_filter_zstd(a: *mut Archive) -> c_int;
        pub fn archive_write_add_filter_bzip2(a: *mut Archive) -> c_int;
        pub fn archive_write_add_filter_lz4(a: *mut Archive) -> c_int;
        pub fn archive_write_open2(
            a: *mut Archive,
            client_data: *mut c_void,
            opener: Option<OpenCallback>,
            writer: Option<WriteCallback>,
            closer: Option<CloseCallback>,
            freer: Option<FreeCallback>,
        ) -> c_int;
        pub fn archive_write_header(a: *mut Archive, entry: *mut ArchiveEntry) -> c_int;
        pub fn archive_write_data(
            a: *mut Archive,
            buffer: *const c_void,
            length: usize,
        ) -> LaSsizeT;
        pub fn archive_write_finish_entry(a: *mut Archive) -> c_int;
        pub fn archive_error_string(a: *mut Archive) -> *const c_char;

        pub fn archive_entry_new() -> *mut ArchiveEntry;
        pub fn archive_entry_free(e: *mut ArchiveEntry);
        pub fn archive_entry_set_pathname_utf8(e: *mut ArchiveEntry, name: *const c_char);
        pub fn archive_entry_set_size(e: *mut ArchiveEntry, size: LaInt64T);
        pub fn archive_entry_unset_size(e: *mut ArchiveEntry);
        pub fn archive_entry_set_filetype(e: *mut ArchiveEntry, filetype: Mode);
        pub fn archive_entry_set_perm(e: *mut ArchiveEntry, perm: Mode);
        pub fn archive_entry_set_mtime(
            e: *mut ArchiveEntry,
            time: TimeT,
            nsec: std::os::raw::c_long,
        );
    }

    /// An opaque `locale_t`: `struct __locale_t*` on bionic, `struct __locale_struct*` on glibc --
    /// never dereferenced by this crate, only round-tripped through `newlocale`/`uselocale`/
    /// `freelocale`.
    pub type LocaleT = *mut c_void;

    /// `LC_CTYPE` is category 0 and `LC_CTYPE_MASK` is `1 << LC_CTYPE` on both bionic
    /// (`$NDK/sysroot/usr/include/locale.h`) and glibc -- confirmed against both headers, and
    /// against a real `newlocale`/`uselocale` round trip on the host (see [super::LocalePin]).
    pub const LC_CTYPE_MASK: c_int = 1;

    /// `nl_langinfo`'s `CODESET` item id: `1` on bionic (`$NDK/sysroot/usr/include/langinfo.h`,
    /// available since API 26, well under this app's minSdk); `14` on glibc, confirmed by
    /// compiling and running a one-line C program against the host's own `<langinfo.h>` --
    /// glibc's `CODESET` is `_NL_CTYPE_CODESET_NAME`, an enum value with no portable numeric
    /// definition of its own, so it cannot be hard-coded as a single cross-platform constant.
    #[cfg(target_os = "android")]
    pub const CODESET: c_int = 1;
    #[cfg(not(target_os = "android"))]
    pub const CODESET: c_int = 14;

    unsafe extern "C" {
        pub fn newlocale(category_mask: c_int, locale: *const c_char, base: LocaleT) -> LocaleT;
        pub fn uselocale(new_locale: LocaleT) -> LocaleT;
        pub fn freelocale(loc: LocaleT);
        pub fn nl_langinfo(item: c_int) -> *mut c_char;
    }
}

const ARCHIVE_OK: c_int = 0;
const ARCHIVE_WARN: c_int = -20;

const AE_IFREG: sys::Mode = 0o100000;
const AE_IFDIR: sys::Mode = 0o040000;

/// One `FZW1` frame's magic; written once, before the first `ENTRY`/`FINISH`/`ABORT`.
pub const MAGIC: &[u8; 4] = b"FZW1";

pub const TAG_ENTRY: u8 = 0x01;
pub const TAG_DATA: u8 = 0x02;
pub const TAG_END: u8 = 0x03;
pub const TAG_FINISH: u8 = 0x04;
pub const TAG_ABORT: u8 = 0x05;

pub const KIND_FILE: u8 = 1;
pub const KIND_DIRECTORY: u8 = 2;

/// The largest path an `ENTRY` frame may carry (matches the sibling `FZX1` codec's own bound).
pub const MAX_PATH_BYTES: usize = 64 * 1024;
/// The largest one `DATA` frame may carry.
pub const MAX_DATA_FRAME_BYTES: usize = 1024 * 1024;
/// The `ENTRY` frame's declared-size sentinel for "unknown".
pub const SIZE_UNKNOWN: i64 = -1;
/// `ENTRY`'s `mtime` sentinel for "the feeder does not know; use the current time".
pub const MTIME_NOW: i64 = 0;

/// Which archive format+filter combination the writer configures (design section 2.4). Every
/// variant maps to one `archive_write_set_format_*`/`archive_write_add_filter_*` pair and one
/// `archive_write_set_options` string built from typed values -- never from user text, so an
/// option-injection attempt in a name or a level can never reach `archive_write_set_options`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WriteFormat {
    Zip,
    TarGz,
    TarXz,
    TarZstd,
    TarBzip2,
    TarLz4,
}

impl WriteFormat {
    /// Whether this format needs every entry's exact size before its header (design section 2.2
    /// step 4, section 2.5): every `tar.*` variant, via pax's fixed-size-at-header-time behaviour;
    /// only `zip` tolerates an unset size (its data descriptor records the true count).
    fn requires_known_size(self) -> bool {
        !matches!(self, WriteFormat::Zip)
    }
}

/// The writer's configuration for one call (design section 2.4): the format/filter pair and a
/// numeric compression level already resolved from the sheet's Fast/Normal/Best tiers -- this
/// module clamps it to the format's own valid range but does not re-derive the tier mapping,
/// which is `CompressPlanner`'s job (a level 0/"store" path exists only for headless callers,
/// never surfaced by the sheet, and is reached by passing `level = 0` with `format = Zip`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FormatOptions {
    pub format: WriteFormat,
    pub level: u32,
}

/// One entry as the frame parser hands it to the [Writer]: already decoded, bounds-checked and
/// path-validated by the time it reaches `add_entry`.
struct EntryMeta {
    is_directory: bool,
    path: String,
    mtime_epoch_secs: i64,
    mode: u32,
    size: Option<u64>,
}

/// What one `write_frames`/`write_frames_io` call produced (design section 2.5):
/// `entries`/`bytes_in` are this module's own counts of the frames it processed (never trust the
/// feeder's own arithmetic); `bytes_out` is the writer callback's own tally of what actually
/// reached `out_fd` -- the "the drain reads it back and confirms `bytes_out`" integrity check the
/// design's Kotlin side runs is checking this number against what it drained.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct WriteReport {
    pub entries: u32,
    pub bytes_in: u64,
    pub bytes_out: u64,
}

/// Pins the calling thread's locale to `C.UTF-8` for as long as it is held, asserting the pin
/// actually took effect (`nl_langinfo(CODESET) == "UTF-8"`) rather than trusting that
/// `newlocale`/`uselocale` succeeding means what it should. Restores the previous locale and frees
/// the new one on drop -- process-wide `uselocale` is thread-scoped by design (`man 3 uselocale`),
/// so this only ever affects the one thread that calls [LocalePin::activate], which for
/// `:decoders:write` is a thread whose only job is this one call (design section 4's own risk
/// note: the blast radius is contained by construction).
struct LocalePin {
    previous: sys::LocaleT,
    installed: sys::LocaleT,
}

impl LocalePin {
    fn activate() -> Result<Self, ArchiveError> {
        let name = CString::new("C.UTF-8").expect("a string literal never contains a NUL");
        // SAFETY: `name` is a valid, NUL-terminated C string alive for the duration of this call;
        // `newlocale` either returns a valid new `locale_t` or null on failure, never partially
        // initialised state this crate must clean up.
        let installed =
            unsafe { sys::newlocale(sys::LC_CTYPE_MASK, name.as_ptr(), std::ptr::null_mut()) };
        if installed.is_null() {
            return Err(ArchiveError::Unsupported(
                "the C.UTF-8 locale is not available on this platform".into(),
            ));
        }
        // SAFETY: `installed` was just created above and is owned by this call until `freelocale`;
        // `uselocale` returns the thread's previous locale (which may be `LC_GLOBAL_LOCALE`, a
        // sentinel value this crate only ever round-trips, never dereferences).
        let previous = unsafe { sys::uselocale(installed) };
        let codeset = current_codeset();
        if codeset != "UTF-8" {
            // SAFETY: restoring exactly what activate() just changed, on the same thread, before
            // returning the failure -- the pin must not outlive a failed activation.
            unsafe {
                sys::uselocale(previous);
                sys::freelocale(installed);
            }
            return Err(ArchiveError::Unsupported(format!(
                "the C.UTF-8 locale did not take effect on this thread (nl_langinfo reported {codeset:?})"
            )));
        }
        Ok(LocalePin {
            previous,
            installed,
        })
    }
}

impl Drop for LocalePin {
    fn drop(&mut self) {
        // SAFETY: `self.previous`/`self.installed` are exactly what `activate` obtained on this
        // same thread; restoring the previous locale and freeing the one this pin installed is
        // the documented `uselocale`/`freelocale` contract, run at most once (no `Clone`).
        unsafe {
            sys::uselocale(self.previous);
            sys::freelocale(self.installed);
        }
    }
}

/// Test-only: pins this thread's locale to `C.UTF-8` for the duration of `body`, exactly like a
/// real `write_frames_io` call does, so `write_tests.rs` can read back a just-written archive's
/// non-ASCII names through this crate's own (locale-sensitive, on the host, per the archive
/// entry API's own lazy MBS/WCS conversion) reader without needing to reach into this module's
/// private [LocalePin]. `#[cfg(test)]` keeps it out of every non-test build entirely.
#[cfg(test)]
pub(crate) fn with_c_utf8_locale_for_test<T>(body: impl FnOnce() -> T) -> T {
    let _pin =
        LocalePin::activate().expect("the C.UTF-8 locale must be available to run this test");
    body()
}

fn current_codeset() -> String {
    // SAFETY: `nl_langinfo` returns either null or a pointer into static/thread-local storage the
    // platform owns; the string is copied out before anything else runs.
    unsafe {
        let ptr = sys::nl_langinfo(sys::CODESET);
        if ptr.is_null() {
            String::new()
        } else {
            std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned()
        }
    }
}

/// The mutable state the write callback shares with [Writer]: the sink itself, whether the writer
/// is poisoned ([Writer::abort]'s mechanism), the last I/O error seen (so a later
/// `archive_write_close`/`archive_write_data` failure can be told apart into `Cancelled` -- the
/// sink's reader went away -- from a genuine fatal error), and the sink's own running byte count.
struct WriteContext<W: Write> {
    sink: W,
    poisoned: Cell<bool>,
    io_error: Option<std::io::Error>,
    bytes_out: u64,
}

/// One open libarchive write handle over a generic sink `W`, generic rather than a boxed
/// `dyn Write` so the fd-based [write_frames] can hand it a plain `&mut File` with no `'static`
/// bound: the write callback is `write_cb::<W>`, a distinct monomorphized `extern "C" fn` per `W`
/// (legal: a generic function's instantiations are ordinary, separately-addressable functions,
/// even though the type itself can never appear in an `extern "C"` signature).
struct Writer<W: Write> {
    raw: *mut sys::Archive,
    /// Owned by libarchive from [Writer::open] until `archive_write_free` runs the matching
    /// `free_cb::<W>`, which is this pointer's only other reader; [Writer::poison] mutates it
    /// through the `Cell` rather than needing `&mut` access to reach across that boundary.
    ctx: *mut WriteContext<W>,
    format: WriteFormat,
}

impl<W: Write> Writer<W> {
    fn open(sink: W, options: &FormatOptions) -> Result<Self, ArchiveError> {
        // SAFETY: `archive_write_new` returns either a valid, freshly allocated `*mut Archive` or
        // null on allocation failure, checked immediately below.
        let raw = unsafe { sys::archive_write_new() };
        if raw.is_null() {
            return Err(ArchiveError::Fatal(
                "archive_write_new returned null".into(),
            ));
        }
        if let Err(error) = configure(raw, options) {
            // SAFETY: `raw` is valid and nothing has opened it yet; freeing it here is exactly
            // what every other error path below does too.
            unsafe { sys::archive_write_free(raw) };
            return Err(error);
        }
        // SAFETY: `raw` is a valid, configured-but-unopened handle.
        unsafe { sys::archive_write_set_bytes_per_block(raw, 0) };
        let ctx = Box::into_raw(Box::new(WriteContext {
            sink,
            poisoned: Cell::new(false),
            io_error: None,
            bytes_out: 0,
        }));
        // SAFETY: `raw` is valid; `ctx` is a live heap allocation this call owns until the `free`
        // callback below runs (libarchive's own documented `archive_write_open2` contract: once
        // registered, `client_data` is freed by the `free` callback when `archive_write_free`
        // runs, whether or not `open2` itself later reports success).
        let status = unsafe {
            sys::archive_write_open2(
                raw,
                ctx as *mut c_void,
                Some(open_cb::<W>),
                Some(write_cb::<W>),
                Some(close_cb::<W>),
                Some(free_cb::<W>),
            )
        };
        if status != ARCHIVE_OK && status != ARCHIVE_WARN {
            let message = error_message(raw);
            // SAFETY: `raw` owns `ctx` from this point regardless of `open2`'s own return value;
            // freeing it runs `free_cb::<W>`, which is what actually drops `ctx`.
            unsafe { sys::archive_write_free(raw) };
            return Err(ArchiveError::Fatal(message));
        }
        Ok(Writer {
            raw,
            ctx,
            format: options.format,
        })
    }

    fn poison(&self) {
        // SAFETY: `self.ctx` is valid for this `Writer`'s whole lifetime (freed only by
        // `archive_write_free` in `Drop`, which runs after every other use).
        unsafe { (*self.ctx).poisoned.set(true) };
    }

    fn add_entry(&mut self, meta: &EntryMeta) -> Result<(), ArchiveError> {
        if let Some(reason) = policy::validate_path(&meta.path, &policy::Limits::default()) {
            return Err(ArchiveError::Failed(reason.to_string()));
        }
        if meta.size.is_none() && !meta.is_directory && self.format.requires_known_size() {
            return Err(ArchiveError::Failed(
                "bad frame: this format requires every entry's size to be known".into(),
            ));
        }
        // SAFETY: `archive_entry_new` returns either a valid pointer or null, checked below.
        let entry = unsafe { sys::archive_entry_new() };
        if entry.is_null() {
            return Err(ArchiveError::Fatal(
                "archive_entry_new returned null".into(),
            ));
        }
        let result = self.write_header(entry, meta);
        // SAFETY: `entry` was allocated immediately above; `archive_write_header` only reads from
        // it (never takes ownership), so freeing it exactly once here, on every path, is correct
        // whether the header call succeeded or not.
        unsafe { sys::archive_entry_free(entry) };
        result
    }

    fn write_header(
        &mut self,
        entry: *mut sys::ArchiveEntry,
        meta: &EntryMeta,
    ) -> Result<(), ArchiveError> {
        let path = CString::new(meta.path.clone())
            .map_err(|_| ArchiveError::Failed("bad frame: path contains a NUL byte".into()))?;
        // SAFETY: `entry` is a valid, freshly allocated, not-yet-shared `archive_entry`; `path` is
        // a valid NUL-terminated C string that outlives this call; every setter here is
        // documented to copy or store what it needs from its argument before returning.
        unsafe {
            sys::archive_entry_set_pathname_utf8(entry, path.as_ptr());
            sys::archive_entry_set_filetype(
                entry,
                if meta.is_directory {
                    AE_IFDIR
                } else {
                    AE_IFREG
                },
            );
            sys::archive_entry_set_perm(entry, (meta.mode & 0o7777) as sys::Mode);
            sys::archive_entry_set_mtime(entry, meta.mtime_epoch_secs as sys::TimeT, 0);
            match meta.size {
                Some(size) => sys::archive_entry_set_size(entry, size as sys::LaInt64T),
                None => sys::archive_entry_unset_size(entry),
            }
        }
        // SAFETY: `self.raw` is a valid, open write handle; `entry` is fully populated above.
        let status = unsafe { sys::archive_write_header(self.raw, entry) };
        self.check(status)
    }

    fn write_data(&mut self, buffer: &[u8]) -> Result<(), ArchiveError> {
        // SAFETY: `self.raw` is a valid, open write handle positioned inside an entry's data (the
        // frame parser never calls this outside one); `buffer` is a plain slice valid for the
        // call's duration.
        let written = unsafe {
            sys::archive_write_data(self.raw, buffer.as_ptr() as *const c_void, buffer.len())
        };
        if written < 0 {
            return self.check(written as c_int);
        }
        Ok(())
    }

    fn finish_entry(&mut self) -> Result<(), ArchiveError> {
        // SAFETY: `self.raw` is a valid, open write handle with a header already written.
        let status = unsafe { sys::archive_write_finish_entry(self.raw) };
        self.check(status)
    }

    /// Sets [WriteContext::poisoned] and returns without closing: the `Drop` impl below still
    /// calls `archive_write_free`, but the poisoned write callback now refuses every further
    /// write, which is what stops that unconditional free from quietly finishing a well-formed
    /// truncated archive (this module's own doc comment; proven, not just asserted, by
    /// `write_tests.rs`'s poisoning test).
    fn abort(self) {
        self.poison();
    }

    /// Closes normally; only ever called when [Self::abort] has not been (the frame parser calls
    /// this exactly once, on `FINISH`, and `abort` on every other exit). Returns the sink's own
    /// tally of bytes actually written, read from [WriteContext::bytes_out] before `Drop` frees
    /// the context that holds it.
    fn close(self) -> Result<u64, ArchiveError> {
        // SAFETY: `self.raw` is a valid, open write handle.
        let status = unsafe { sys::archive_write_close(self.raw) };
        let result = self.check(status);
        // SAFETY: `self.ctx` is still valid here -- `Drop` (which frees it) has not run yet.
        let bytes_out = unsafe { (*self.ctx).bytes_out };
        result.map(|()| bytes_out)
    }

    /// A non-OK/WARN status into this crate's error type, consulting [WriteContext::io_error] so
    /// a broken pipe on the sink (the app process closed its read end -- a cancel) is reported as
    /// [ArchiveError::Cancelled] rather than a bare "fatal" message with no actionable cause.
    fn check(&self, status: c_int) -> Result<(), ArchiveError> {
        if status == ARCHIVE_OK || status == ARCHIVE_WARN {
            return Ok(());
        }
        // SAFETY: `self.ctx` is valid for this `Writer`'s whole lifetime.
        let broken_pipe = unsafe {
            (*self.ctx)
                .io_error
                .as_ref()
                .is_some_and(|error| error.kind() == std::io::ErrorKind::BrokenPipe)
        };
        if broken_pipe {
            return Err(ArchiveError::Cancelled);
        }
        Err(ArchiveError::Fatal(error_message(self.raw)))
    }
}

impl<W: Write> Drop for Writer<W> {
    fn drop(&mut self) {
        // SAFETY: `self.raw` was allocated by `archive_write_new` in `Writer::open` and is freed
        // nowhere else; `Writer` has no `Clone`, so this runs at most once per handle. Freeing
        // triggers `free_cb::<W>`, which drops the boxed `WriteContext<W>` (`self.ctx`) exactly
        // once, whether the write was closed cleanly or poisoned.
        unsafe { sys::archive_write_free(self.raw) };
    }
}

fn error_message(raw: *mut sys::Archive) -> String {
    // SAFETY: `raw` is a valid, live archive handle for the duration of this call.
    unsafe {
        let ptr = sys::archive_error_string(raw);
        if ptr.is_null() {
            "libarchive write error".to_string()
        } else {
            std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned()
        }
    }
}

fn configure(raw: *mut sys::Archive, options: &FormatOptions) -> Result<(), ArchiveError> {
    let format_status = match options.format {
        // SAFETY: `raw` is a valid, freshly allocated, not-yet-opened handle.
        WriteFormat::Zip => unsafe { sys::archive_write_set_format_zip(raw) },
        WriteFormat::TarGz
        | WriteFormat::TarXz
        | WriteFormat::TarZstd
        | WriteFormat::TarBzip2
        | WriteFormat::TarLz4 => unsafe { sys::archive_write_set_format_pax_restricted(raw) },
    };
    check_setup(raw, format_status)?;
    let filter_status = match options.format {
        WriteFormat::Zip => ARCHIVE_OK,
        // SAFETY: as above.
        WriteFormat::TarGz => unsafe { sys::archive_write_add_filter_gzip(raw) },
        WriteFormat::TarXz => unsafe { sys::archive_write_add_filter_xz(raw) },
        WriteFormat::TarZstd => unsafe { sys::archive_write_add_filter_zstd(raw) },
        WriteFormat::TarBzip2 => unsafe { sys::archive_write_add_filter_bzip2(raw) },
        WriteFormat::TarLz4 => unsafe { sys::archive_write_add_filter_lz4(raw) },
    };
    check_setup(raw, filter_status)?;
    let option_string = option_string_for(options);
    let options_c = CString::new(option_string)
        .map_err(|_| ArchiveError::Fatal("the options string contained a NUL byte".into()))?;
    // SAFETY: `raw` is valid; `options_c` is a valid C string alive for the call's duration.
    let options_status = unsafe { sys::archive_write_set_options(raw, options_c.as_ptr()) };
    check_setup(raw, options_status)
}

fn check_setup(raw: *mut sys::Archive, status: c_int) -> Result<(), ArchiveError> {
    if status == ARCHIVE_OK || status == ARCHIVE_WARN {
        Ok(())
    } else {
        Err(ArchiveError::Fatal(error_message(raw)))
    }
}

/// The typed [FormatOptions] as an `archive_write_set_options` string (design section 2.4): every
/// format also pins `hdrcharset=UTF-8` (the module name is `"zip"` for the ZIP writer and `"pax"`
/// for the pax-restricted writer both tar variants and the plain `pax` format share, per
/// `SURVEY-M35-CREATE.md` section 2.4). `tar.gz` additionally turns off the gzip filter's embedded
/// wall-clock timestamp (`gzip:timestamp=0`) unconditionally: nothing in this app's own UI ever
/// promised a create's `.tar.gz` header would carry the moment it was written (only its entries'
/// own `mtime`s are ever shown), and a deterministic filter header is what lets a golden fixture
/// prove writer drift at all (design section 2.8's own reason for wanting one).
fn option_string_for(options: &FormatOptions) -> String {
    match options.format {
        WriteFormat::Zip if options.level == 0 => {
            "zip:compression=store,zip:hdrcharset=UTF-8".to_string()
        }
        WriteFormat::Zip => format!(
            "zip:compression=deflate,zip:compression-level={},zip:hdrcharset=UTF-8",
            options.level.min(9)
        ),
        WriteFormat::TarGz => format!(
            "pax:hdrcharset=UTF-8,gzip:compression-level={},gzip:timestamp=0",
            options.level.min(9)
        ),
        WriteFormat::TarXz => format!(
            "pax:hdrcharset=UTF-8,xz:compression-level={},xz:threads=1",
            options.level.min(9)
        ),
        WriteFormat::TarZstd => format!(
            "pax:hdrcharset=UTF-8,zstd:compression-level={}",
            options.level.clamp(1, 22)
        ),
        WriteFormat::TarBzip2 => format!(
            "pax:hdrcharset=UTF-8,bzip2:compression-level={}",
            options.level.clamp(1, 9)
        ),
        WriteFormat::TarLz4 => format!(
            "pax:hdrcharset=UTF-8,lz4:compression-level={}",
            options.level.clamp(1, 9)
        ),
    }
}

/// `W` is unused in the body: it exists only so `Writer::open`'s call site can name a distinct
/// monomorphization per sink type (`Some(open_cb::<W>)`), matching [write_cb]/[free_cb]'s own
/// generic parameter -- `archive_write_open2` does not care that this particular callback ignores
/// it.
#[allow(clippy::extra_unused_type_parameters)]
unsafe extern "C" fn open_cb<W: Write>(_a: *mut sys::Archive, _client_data: *mut c_void) -> c_int {
    // Nothing to do: the sink is already open by the time `Writer::open` registers it.
    ARCHIVE_OK
}

/// As [open_cb]: `W` is unused in the body, kept only so this callback's type matches the others
/// `archive_write_open2` is registered with.
#[allow(clippy::extra_unused_type_parameters)]
unsafe extern "C" fn close_cb<W: Write>(_a: *mut sys::Archive, _client_data: *mut c_void) -> c_int {
    // The sink is caller-owned and closed by the caller, exactly like the read-side `Reader`
    // never closing its fd; there is nothing of this crate's own to flush here (`write_all`
    // already fully drains every write callback).
    ARCHIVE_OK
}

unsafe extern "C" fn free_cb<W: Write>(_a: *mut sys::Archive, client_data: *mut c_void) -> c_int {
    // SAFETY: `client_data` is exactly the pointer `Writer::open` obtained from
    // `Box::into_raw(Box::new(WriteContext<W>))`, and `archive_write_free` calls the `free`
    // callback registered by `archive_write_open2` exactly once, which is what makes this the one
    // place that reconstructs and drops the `Box` -- never twice, never never.
    unsafe { drop(Box::from_raw(client_data as *mut WriteContext<W>)) };
    ARCHIVE_OK
}

unsafe extern "C" fn write_cb<W: Write>(
    _a: *mut sys::Archive,
    client_data: *mut c_void,
    buffer: *const c_void,
    length: usize,
) -> sys::LaSsizeT {
    // SAFETY: `client_data` points at a live `WriteContext<W>` for the whole time libarchive may
    // invoke this callback (from `archive_write_open2` until the matching `free_cb::<W>` runs);
    // `buffer`/`length` describe a block libarchive owns for exactly the duration of this call.
    let context = unsafe { &mut *(client_data as *mut WriteContext<W>) };
    if context.poisoned.get() {
        // The poisoning mechanism itself (this module's own doc comment): once set, every further
        // write callback refuses, whatever `archive_write_close`/`archive_write_free` next try.
        return -1;
    }
    // SAFETY: `buffer` is non-null with at least `length` readable bytes for this call's duration
    // (libarchive's own `archive_write_callback` contract).
    let slice = unsafe { std::slice::from_raw_parts(buffer as *const u8, length) };
    match context.sink.write_all(slice) {
        Ok(()) => {
            context.bytes_out = context.bytes_out.saturating_add(length as u64);
            length as sys::LaSsizeT
        }
        Err(error) => {
            context.poisoned.set(true);
            context.io_error = Some(error);
            -1
        }
    }
}

/// One entry the frame parser is currently streaming `DATA` for: its ordinal (so a `DATA`/`END`
/// naming a different one is a protocol violation, not a silent misattribution), its declared
/// size when the feeder gave one, and how many bytes have been sent for it so far.
struct OpenEntry {
    ordinal: u32,
    declared: Option<u64>,
    written: u64,
}

fn protocol_violation(message: impl Into<String>) -> ArchiveError {
    ArchiveError::Failed(format!("bad frame: {}", message.into()))
}

fn truncated_stream() -> ArchiveError {
    protocol_violation("the frame stream ended before FINISH or ABORT")
}

/// Reads exactly `buffer.len()` bytes, `Ok(true)` on success; `Ok(false)` only when the very
/// first read of this call hit a clean end of stream (used solely for the magic itself, so a
/// completely empty `in_fd` is reported the same way a truncated one further in is: a protocol
/// violation, never a panic or a hang).
fn fill_or_eof<R: Read>(input: &mut R, buffer: &mut [u8]) -> Result<bool, ArchiveError> {
    let mut total = 0usize;
    while total < buffer.len() {
        match input.read(&mut buffer[total..]) {
            Ok(0) => {
                if total == 0 {
                    return Ok(false);
                }
                return Err(truncated_stream());
            }
            Ok(n) => total += n,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => {
                return Err(ArchiveError::Fatal(format!(
                    "reading the frame stream: {error}"
                )))
            }
        }
    }
    Ok(true)
}

/// [fill_or_eof], requiring the bytes to be present (every read after the magic itself).
fn fill<R: Read>(input: &mut R, buffer: &mut [u8]) -> Result<(), ArchiveError> {
    if fill_or_eof(input, buffer)? {
        Ok(())
    } else {
        Err(truncated_stream())
    }
}

fn read_u8<R: Read>(input: &mut R) -> Result<u8, ArchiveError> {
    let mut buffer = [0u8; 1];
    fill(input, &mut buffer)?;
    Ok(buffer[0])
}

fn read_u16<R: Read>(input: &mut R) -> Result<u16, ArchiveError> {
    let mut buffer = [0u8; 2];
    fill(input, &mut buffer)?;
    Ok(u16::from_le_bytes(buffer))
}

fn read_u32<R: Read>(input: &mut R) -> Result<u32, ArchiveError> {
    let mut buffer = [0u8; 4];
    fill(input, &mut buffer)?;
    Ok(u32::from_le_bytes(buffer))
}

fn read_u64<R: Read>(input: &mut R) -> Result<u64, ArchiveError> {
    let mut buffer = [0u8; 8];
    fill(input, &mut buffer)?;
    Ok(u64::from_le_bytes(buffer))
}

fn read_i64<R: Read>(input: &mut R) -> Result<i64, ArchiveError> {
    let mut buffer = [0u8; 8];
    fill(input, &mut buffer)?;
    Ok(i64::from_le_bytes(buffer))
}

/// `ENTRY`'s `mtime` field to a `time_t`-safe epoch-seconds value: `0` ("now") resolves to the
/// wall clock, otherwise milliseconds truncate to seconds and clamp to a 32-bit range (armv7
/// bionic's `time_t` is 32-bit -- `lib.rs`'s own `sys::TimeT` doc comment).
fn mtime_seconds_from_frame(mtime_epoch_millis: i64) -> i64 {
    if mtime_epoch_millis == MTIME_NOW {
        return std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_secs() as i64)
            .unwrap_or(0);
    }
    (mtime_epoch_millis / 1000).clamp(i32::MIN as i64, i32::MAX as i64)
}

/// The `io`-based core (design section 2.5): reads `FZW1` frames from `input`, drives a [Writer]
/// over `output`, and returns [WriteReport] or the [ArchiveError] the design's own frame grammar
/// and size rules name. Generic so a fuzz target and `write_tests.rs`'s plain-JVM-style blocking
/// pipe tests can drive it without a real fd (`write_frames` below is the thin fd adapter). Pins
/// the calling thread's locale for the whole call ([LocalePin]); every exit path -- `FINISH`,
/// `ABORT`, a protocol violation, an internal libarchive error -- runs through exactly one of
/// [Writer::close]/[Writer::abort], so the writer is never both left open and dropped.
pub fn write_frames_io<R: Read, W: Write>(
    mut input: R,
    output: W,
    options: &FormatOptions,
) -> Result<WriteReport, ArchiveError> {
    let _pin = LocalePin::activate()?;
    let mut magic = [0u8; 4];
    if !fill_or_eof(&mut input, &mut magic)? {
        return Err(protocol_violation("the frame stream is empty"));
    }
    if &magic != MAGIC {
        return Err(protocol_violation("missing the FZW1 magic"));
    }
    let mut writer = Writer::open(output, options)?;
    let mut report = WriteReport::default();
    let mut last_ordinal: Option<u32> = None;
    let mut open: Option<OpenEntry> = None;

    let outcome = run_frames(
        &mut input,
        &mut writer,
        &mut report,
        &mut last_ordinal,
        &mut open,
    );
    match outcome {
        Ok(()) => writer.close().map(|bytes_out| WriteReport {
            entries: report.entries,
            bytes_in: report.bytes_in,
            bytes_out,
        }),
        Err(error) => {
            writer.abort();
            Err(error)
        }
    }
}

/// The frame loop itself, factored out of [write_frames_io] so every exit -- `Ok(())` on
/// `FINISH`, `Err` on `ABORT` or a protocol violation -- goes through that function's own single
/// `close`/`abort` dispatch rather than being duplicated at each `return` site.
fn run_frames<R: Read, W: Write>(
    input: &mut R,
    writer: &mut Writer<W>,
    report: &mut WriteReport,
    last_ordinal: &mut Option<u32>,
    open: &mut Option<OpenEntry>,
) -> Result<(), ArchiveError> {
    loop {
        match read_u8(input)? {
            TAG_ENTRY => handle_entry(input, writer, report, last_ordinal, open)?,
            TAG_DATA => handle_data(input, writer, report, open)?,
            TAG_END => handle_end(input, writer, report, open)?,
            TAG_FINISH => {
                if open.is_some() {
                    return Err(protocol_violation("FINISH while an entry is open"));
                }
                return Ok(());
            }
            TAG_ABORT => {
                if open.is_some() {
                    return Err(protocol_violation("ABORT while an entry is open"));
                }
                let message_len = read_u16(input)? as usize;
                let mut message = vec![0u8; message_len];
                fill(input, &mut message)?;
                // The message is carried for whoever logs the abort (the queue side); this
                // module's own contract is the return value, not the text.
                let _ = String::from_utf8_lossy(&message);
                return Err(ArchiveError::Cancelled);
            }
            other => return Err(protocol_violation(format!("unknown frame tag {other}"))),
        }
    }
}

fn handle_entry<R: Read, W: Write>(
    input: &mut R,
    writer: &mut Writer<W>,
    report: &mut WriteReport,
    last_ordinal: &mut Option<u32>,
    open: &mut Option<OpenEntry>,
) -> Result<(), ArchiveError> {
    if open.is_some() {
        return Err(protocol_violation(
            "ENTRY while another entry is already open",
        ));
    }
    let ordinal = read_u32(input)?;
    if last_ordinal.is_some_and(|last| ordinal <= last) {
        return Err(protocol_violation(format!(
            "ordinal {ordinal} did not increase past the previous entry"
        )));
    }
    *last_ordinal = Some(ordinal);
    let kind = read_u8(input)?;
    let is_directory = match kind {
        KIND_FILE => false,
        KIND_DIRECTORY => true,
        other => return Err(protocol_violation(format!("unknown entry kind {other}"))),
    };
    let declared_size = read_i64(input)?;
    let size = if declared_size < 0 {
        None
    } else {
        Some(declared_size as u64)
    };
    let mtime_epoch_millis = read_i64(input)?;
    let mode = read_u32(input)?;
    let path_len = read_u32(input)? as usize;
    if path_len > MAX_PATH_BYTES {
        return Err(protocol_violation(format!(
            "path is longer than {MAX_PATH_BYTES} bytes"
        )));
    }
    let mut path_bytes = vec![0u8; path_len];
    fill(input, &mut path_bytes)?;
    let path =
        String::from_utf8(path_bytes).map_err(|_| protocol_violation("path is not valid UTF-8"))?;

    let meta = EntryMeta {
        is_directory,
        path,
        mtime_epoch_secs: mtime_seconds_from_frame(mtime_epoch_millis),
        mode,
        size: if is_directory { None } else { size },
    };
    writer.add_entry(&meta)?;
    if is_directory {
        // A directory ENTRY takes no DATA/END of its own (this module's own doc comment): it is
        // complete the moment its header is written.
        writer.finish_entry()?;
        report.entries = report.entries.saturating_add(1);
    } else {
        *open = Some(OpenEntry {
            ordinal,
            declared: size,
            written: 0,
        });
    }
    Ok(())
}

fn handle_data<R: Read, W: Write>(
    input: &mut R,
    writer: &mut Writer<W>,
    report: &mut WriteReport,
    open: &mut Option<OpenEntry>,
) -> Result<(), ArchiveError> {
    let ordinal = read_u32(input)?;
    let len = read_u32(input)? as usize;
    if len == 0 || len > MAX_DATA_FRAME_BYTES {
        return Err(protocol_violation(format!(
            "DATA length {len} is outside 1..={MAX_DATA_FRAME_BYTES}"
        )));
    }
    let entry = open
        .as_mut()
        .ok_or_else(|| protocol_violation("DATA with no open entry"))?;
    if entry.ordinal != ordinal {
        return Err(protocol_violation(format!(
            "DATA names ordinal {ordinal} but {} is open",
            entry.ordinal
        )));
    }
    if let Some(declared) = entry.declared {
        if entry.written.saturating_add(len as u64) > declared {
            // The one size condition worth aborting the whole write for (this module's own doc
            // comment, and the design's own risk note): a source growing under the feeder while
            // it is fed, not a benign shortfall.
            return Err(protocol_violation(format!(
                "entry {ordinal} sent more data than its declared size of {declared} bytes"
            )));
        }
    }
    let mut buffer = vec![0u8; len];
    fill(input, &mut buffer)?;
    writer.write_data(&buffer)?;
    entry.written = entry.written.saturating_add(len as u64);
    report.bytes_in = report.bytes_in.saturating_add(len as u64);
    Ok(())
}

fn handle_end<R: Read, W: Write>(
    input: &mut R,
    writer: &mut Writer<W>,
    report: &mut WriteReport,
    open: &mut Option<OpenEntry>,
) -> Result<(), ArchiveError> {
    let ordinal = read_u32(input)?;
    let byte_count = read_u64(input)?;
    let entry = open
        .take()
        .ok_or_else(|| protocol_violation("END with no open entry"))?;
    if entry.ordinal != ordinal {
        return Err(protocol_violation(format!(
            "END names ordinal {ordinal} but {} is open",
            entry.ordinal
        )));
    }
    if byte_count != entry.written {
        return Err(protocol_violation(format!(
            "END claims {byte_count} bytes for entry {ordinal} but {} were sent",
            entry.written
        )));
    }
    writer.finish_entry()?;
    report.entries = report.entries.saturating_add(1);
    Ok(())
}

/// The fd-based adapter (design section 2.5): both descriptors are caller-owned for the whole
/// call and are never closed here, exactly like every other export in this crate ([crate::extract],
/// [crate::inspect_into], ...). Reuses [crate::borrow_fd]'s `ManuallyDrop<File>` wrapper so
/// neither descriptor's lifetime is ever this function's to manage.
pub fn write_frames(
    in_fd: RawFd,
    out_fd: RawFd,
    options: &FormatOptions,
) -> Result<WriteReport, ArchiveError> {
    let mut input = crate::borrow_fd(in_fd);
    let mut output = crate::borrow_fd(out_fd);
    write_frames_io(&mut *input, &mut *output, options)
}
