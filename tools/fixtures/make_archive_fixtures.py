#!/usr/bin/env python3
"""Generates the archive-format fixtures under `core/fixtures/archives/` that `fylz-archive`'s
M3.2 tests read (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.7): the ZIP, 7-Zip and ISO 9660
files whose listing or data needs a *seek*, so the tests can show what a seekable descriptor buys
over a pipe, plus the hostile files the extraction policy must refuse end to end. M3.3
(`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.10) adds the browsing fixtures: nested
archives at the depth bound and one past it, implicit directories, a mixed bag of entry types,
links and special files, every messy path shape a real archive carries, a backslash-separated
ZIP name, and a tar damaged after its third header.

Every fixture is **deterministic** -- running this script twice must produce byte-identical files,
and the script checks that itself (it builds everything twice in memory and compares before
writing anything). What makes that true, library by library:

- `TZ=UTC` is forced before the libraries are imported: pycdlib formats directory-record dates
  with `time.localtime`, and py7zr computes its UTC offset at import time.
- `time.time` is patched to `FIXED_EPOCH` for the whole build. pycdlib stamps every volume
  descriptor and directory record with `time.time()`; py7zr's `writestr` stamps the entry with
  the current time too (`ArchiveTimestamp.from_now`). `writestr` rather than `write(path)`
  because the latter records `st_ctime`, which `os.utime` cannot set.
- `zipfile` entries get a fixed DOS timestamp and explicit Unix mode bits.
- `sample-copy.7z` calls `set_encoded_header_mode(False)`: py7zr otherwise LZMA2-compresses the
  archive header even when the entries use the COPY filter, and the point of that fixture is a 7z
  readable without liblzma at all.
- `sample-encrypted-header.7z` patches py7zr's AES IV source (`Cryptodome.Random.get_random_bytes`)
  with a fixed byte pattern. This is a *fixture*, never a real archive: its only job is to make
  libarchive say "The header is encrypted, but currently not supported".
- Names are added in sorted order; nothing depends on directory iteration.

Needs the Python `py7zr` and `pycdlib` packages, at the versions below (a different version may
lay the bytes out differently, which the determinism check is there to catch), and `zstandard`
for the compressed many-entries tar -- the same package `make_compression_fixtures.py` uses:

    pip install py7zr==1.1.3 pycdlib==1.20.0 zstandard

(PyPI is reachable in this project's build environment; a virtualenv with
`--system-site-packages` is fine.) Fails loudly naming exactly what to install rather than
silently skipping a fixture. Everything else is the standard library (`zipfile`, `tarfile`).

Every output is kept under 128 KiB (asserted). The script prints each file's SHA-256 so the
values can be recorded in `docs/agent/PROGRESS.md`.

Usage: python3 tools/fixtures/make_archive_fixtures.py
"""

from __future__ import annotations

import hashlib
import io
import os
import struct
import tarfile
import time
import zipfile
from pathlib import Path
from typing import Callable

# Before any third-party import: see the module doc comment (pycdlib's `localtime`, py7zr's
# import-time UTC offset).
os.environ["TZ"] = "UTC"
if hasattr(time, "tzset"):
    time.tzset()

FIXTURES_DIR = Path(__file__).resolve().parent.parent.parent / "core" / "fixtures" / "archives"
HOSTILE_DIR = FIXTURES_DIR / "hostile"

MAX_FIXTURE_BYTES = 128 * 1024

# 2020-01-01T00:00:00Z: the same instant `fylz-archive`'s own tar-building test helper stamps
# (`--mtime='2020-01-01 00:00:00 UTC'`), so every fixture in this repo agrees on one timestamp.
FIXED_EPOCH = 1_577_836_800
DOS_DATE_TIME = (2020, 1, 1, 0, 0, 0)

# The sample tree shared by every non-hostile fixture: five files in two top-level directories,
# one of them nested. `hello.txt` carries the same bytes as every other fixture family in this
# repo (`make_compression_fixtures.py`'s ENTRIES and lib.rs's `ar` builder). The two `.bin`
# members are deliberately incompressible-ish and compressible respectively, so a deflate/LZMA2
# fixture actually exercises the codec rather than storing everything.
TREE: list[tuple[str, bytes]] = sorted(
    [
        ("hello.txt", b"hello world"),
        ("docs/readme.md", b"# Fylz archive fixture\n\nFive files in two directories, one nested.\n"),
        ("docs/notes/todo.txt", b"- prove the seekable reader reads sizes from the central directory\n" * 4),
        ("images/pixel.bin", bytes((i * 131 + 7) % 256 for i in range(256))),
        ("images/gradient.bin", bytes(range(256)) * 4),
    ]
)
DIRECTORIES: list[str] = ["docs/", "docs/notes/", "images/"]

MANY_ENTRIES_COUNT = 10_001

# The M3.3 depth bound (`ArchiveDocumentId.MAX_DEPTH`): `nested-depth-4.zip` has exactly this many
# archive levels and opens to its innermost file; `nested-depth-5.zip` has one more and its fifth
# level is refused at id construction.
NESTED_DEPTH_LIMIT = 4


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# --------------------------------------------------------------------------------------------
# ZIP
# --------------------------------------------------------------------------------------------


def zip_entry(name: str, directory: bool = False) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=DOS_DATE_TIME)
    if directory:
        info.external_attr = (0o40755 << 16) | 0x10
        info.compress_type = zipfile.ZIP_STORED
    else:
        info.external_attr = 0o644 << 16
        info.compress_type = zipfile.ZIP_DEFLATED
    return info


def write_tree(zf: zipfile.ZipFile) -> None:
    for directory in DIRECTORIES:
        zf.writestr(zip_entry(directory, directory=True), b"")
    for name, contents in TREE:
        zf.writestr(zip_entry(name), contents)


def build_cd_zip() -> bytes:
    """The baseline: an ordinary ZIP whose central directory carries every size (bit 3 clear)."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        write_tree(zf)
    data = buf.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for info in zf.infolist():
            assert not info.flag_bits & 0x08, f"{info.filename}: unexpected data descriptor"
    return data


class Unseekable:
    """A write-only sink with no `seek`/`tell`, which makes `zipfile` write the streamed ZIP
    shape: local headers with zero sizes/CRC, bit 3 set, and a data descriptor after each
    entry's data -- the shape a stream-writing tool (or a web server zipping on the fly) produces.
    `zipfile` wraps it in its own `_Tellable` once `tell()` raises."""

    def __init__(self) -> None:
        self.buf = io.BytesIO()

    def write(self, data: bytes) -> int:
        return self.buf.write(data)

    def flush(self) -> None:
        pass


def build_streamed_zip() -> bytes:
    sink = Unseekable()
    with zipfile.ZipFile(sink, "w", compression=zipfile.ZIP_DEFLATED) as zf:  # type: ignore[arg-type]
        write_tree(zf)
    data = sink.buf.getvalue()
    # Verified, not assumed: every file entry has bit 3 and a local header whose CRC and both
    # sizes are zero, so a reader that cannot seek to the central directory learns no sizes from
    # the headers it streams past.
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            assert info.flag_bits & 0x08, f"{info.filename}: bit 3 not set"
            header = struct.unpack("<4s2B4HL2L2H", data[info.header_offset : info.header_offset + 30])
            _sig, _ver, _sys, flags, _method, _mtime, _mdate, crc, csize, usize = header[:10]
            assert flags & 0x08 and crc == 0 and csize == 0 and usize == 0, (info.filename, header)
    return data


def build_zip_slip() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr(zip_entry("innocent.txt"), b"fine\n")
        zf.writestr(zip_entry("../evil"), b"pwned\n")
    data = buf.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        assert zf.namelist() == ["innocent.txt", "../evil"], zf.namelist()
    return data


def build_absolute_path_zip() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr(zip_entry("innocent.txt"), b"fine\n")
        zf.writestr(zip_entry("/etc/passwd"), b"root:x:0:0::/root:/bin/sh\n")
    data = buf.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        assert zf.namelist() == ["innocent.txt", "/etc/passwd"], zf.namelist()
    return data


# --------------------------------------------------------------------------------------------
# 7-Zip
# --------------------------------------------------------------------------------------------


def import_py7zr():
    try:
        import py7zr
    except ImportError as exc:
        raise SystemExit(
            "make_archive_fixtures.py needs the Python `py7zr` package for the 7z fixtures -- "
            "install it with `pip install py7zr==1.1.3`."
        ) from exc
    return py7zr


def build_7z(
    filters: list[dict[str, int]] | None,
    *,
    encoded_header: bool = True,
    password: str | None = None,
    header_encryption: bool = False,
) -> bytes:
    py7zr = import_py7zr()
    buf = io.BytesIO()
    with py7zr.SevenZipFile(
        buf, "w", filters=filters, password=password, header_encryption=header_encryption
    ) as archive:
        if not encoded_header:
            archive.set_encoded_header_mode(False)
        for name, contents in TREE:
            archive.writestr(contents, name)
    data = buf.getvalue()
    if password is None:
        with py7zr.SevenZipFile(io.BytesIO(data)) as archive:
            names = sorted(archive.getnames())
            assert names == [name for name, _ in TREE], names
    return data


def build_copy_7z() -> bytes:
    """COPY-filtered entries and a *plain* header: the whole file is readable without liblzma.
    Listing means reading the header at the end of the file; reading an entry's data means a seek
    back to its pack stream -- the backward seek a pipe cannot do."""
    py7zr = import_py7zr()
    data = build_7z([{"id": py7zr.FILTER_COPY}], encoded_header=False)
    # The start header points at the (unencoded) header; property id 0x01 is kHeader, 0x17 is
    # kEncodedHeader. Checked so a py7zr change cannot silently hand back an encoded header.
    next_header_offset = struct.unpack_from("<Q", data, 12)[0]
    header_id = data[32 + next_header_offset]
    assert header_id == 0x01, f"expected a plain kHeader (0x01), got 0x{header_id:02x}"
    return data


def build_lzma2_7z() -> bytes:
    data = build_7z(None)
    next_header_offset = struct.unpack_from("<Q", data, 12)[0]
    header_id = data[32 + next_header_offset]
    assert header_id == 0x17, f"expected an encoded header (0x17), got 0x{header_id:02x}"
    return data


def build_encrypted_header_7z() -> bytes:
    """A 7z whose header itself is AES-encrypted: libarchive cannot even list it, and reports
    "The header is encrypted, but currently not supported" -- `ArchiveError::Unsupported`. The
    IV source is patched so the bytes are reproducible; this is a fixture, not a real secret."""
    py7zr = import_py7zr()
    import py7zr.compressor as compressor

    real_random = compressor.get_random_bytes
    compressor.get_random_bytes = lambda n: bytes((i * 37 + 11) % 256 for i in range(n))
    try:
        data = build_7z(
            [{"id": py7zr.FILTER_COPY}, {"id": py7zr.FILTER_CRYPTO_AES256_SHA256}],
            password="fixture-only",
            header_encryption=True,
        )
    finally:
        compressor.get_random_bytes = real_random
    next_header_offset = struct.unpack_from("<Q", data, 12)[0]
    header_id = data[32 + next_header_offset]
    assert header_id == 0x17, f"expected an encoded (encrypted) header (0x17), got 0x{header_id:02x}"
    with py7zr.SevenZipFile(io.BytesIO(data), password="fixture-only") as archive:
        assert sorted(archive.getnames()) == [name for name, _ in TREE]
    return data


# --------------------------------------------------------------------------------------------
# ISO 9660
# --------------------------------------------------------------------------------------------


def iso_name(name: str) -> str:
    """The ISO 9660 identifier for a sample-tree path: upper case, `;1` version, level-3 names."""
    return "/" + name.upper() + ";1"


def build_iso() -> bytes:
    try:
        import pycdlib
    except ImportError as exc:
        raise SystemExit(
            "make_archive_fixtures.py needs the Python `pycdlib` package for the ISO fixture -- "
            "install it with `pip install pycdlib==1.20.0`."
        ) from exc
    iso = pycdlib.PyCdlib()
    iso.new(interchange_level=3, joliet=3, rock_ridge="1.09", vol_ident="FYLZFIXTURE")
    for directory in DIRECTORIES:
        stripped = directory.rstrip("/")
        iso.add_directory(
            "/" + stripped.upper(),
            rr_name=stripped.rsplit("/", 1)[-1],
            joliet_path="/" + stripped,
            file_mode=0o040755,
        )
    for name, contents in TREE:
        iso.add_fp(
            io.BytesIO(contents),
            len(contents),
            iso_name(name),
            rr_name=name.rsplit("/", 1)[-1],
            joliet_path="/" + name,
            file_mode=0o100644,
        )
    out = io.BytesIO()
    iso.write_fp(out)
    iso.close()
    return out.getvalue()


# --------------------------------------------------------------------------------------------
# tar
# --------------------------------------------------------------------------------------------


def tar_member(name: str, size: int = 0, mode: int = 0o644) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.size = size
    info.mode = mode
    info.mtime = FIXED_EPOCH
    return info


def build_symlink_escape_tar() -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        tar.addfile(tar_member("innocent.txt", size=5), io.BytesIO(b"fine\n"))
        link = tar_member("escape", mode=0o777)
        link.type = tarfile.SYMTYPE
        link.linkname = "/etc/passwd"
        tar.addfile(link)
    return buf.getvalue()


def build_many_entries_tar_zst() -> bytes:
    """10,001 zero-length members: one over the policy's `max_entries` default (10,000), so the
    "too many entries" rule fires on a real file. About 5 MB of tar headers, shipped zstd-compressed
    (a few KB): libarchive's zstd filter is compiled in, so the engine reads it directly."""
    try:
        import zstandard
    except ImportError as exc:
        raise SystemExit(
            "make_archive_fixtures.py needs the Python `zstandard` package for the many-entries "
            "fixture -- install it with `pip install zstandard`."
        ) from exc
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for index in range(MANY_ENTRIES_COUNT):
            tar.addfile(tar_member(f"e{index:05d}"))
    return zstandard.ZstdCompressor(level=19).compress(buf.getvalue())


# --------------------------------------------------------------------------------------------
# M3.3 browsing fixtures
# --------------------------------------------------------------------------------------------


def stored_zip(entries: list[tuple[str, bytes]]) -> bytes:
    """A ZIP of stored (uncompressed) members with fixed timestamps and modes -- the shape for a
    ZIP that contains other ZIPs (already compressed) and for the small hand-set-name cases."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, contents in entries:
            info = zip_entry(name)
            info.compress_type = zipfile.ZIP_STORED
            zf.writestr(info, contents)
    return buf.getvalue()


def build_nested_zip(levels: int) -> bytes:
    """`levels` archive levels: the outermost ZIP holds `level2.zip`, which holds `level3.zip`,
    ... down to the innermost ZIP, which holds `innermost.txt` and a `readme.txt` naming its
    depth. Every level also carries a `readme.txt` so each is browsable in its own right."""
    inner = stored_zip(
        [
            ("innermost.txt", b"the innermost file, %d archive levels down\n" % levels),
            ("readme.txt", b"level %d of %d\n" % (levels, levels)),
        ]
    )
    for level in range(levels - 1, 0, -1):
        inner = stored_zip(
            [
                ("level%d.zip" % (level + 1), inner),
                ("readme.txt", b"level %d of %d\n" % (level, levels)),
            ]
        )
    return inner


def build_nested_depth_4_zip() -> bytes:
    return build_nested_zip(NESTED_DEPTH_LIMIT)


def build_nested_depth_5_zip() -> bytes:
    return build_nested_zip(NESTED_DEPTH_LIMIT + 1)


def build_implicit_dirs_zip() -> bytes:
    """Files two levels down with no directory rows at all (the shape `zip -r` without `-D`
    avoids but many tools produce): the tree must synthesise `a/` and `a/b/`."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(zip_entry("a/b/c.txt"), b"c\n")
        zf.writestr(zip_entry("a/d.txt"), b"d\n")
        zf.writestr(zip_entry("top.txt"), b"top\n")
    data = buf.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        assert zf.namelist() == ["a/b/c.txt", "a/d.txt", "top.txt"], zf.namelist()
        assert not any(info.is_dir() for info in zf.infolist())
    return data


def minimal_pdf() -> bytes:
    """One blank page, hand-written with a correct cross-reference table so `PdfRenderer` opens it."""
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] >>",
    ]
    out = io.BytesIO()
    out.write(b"%PDF-1.4\n")
    offsets = []
    for index, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(b"%d 0 obj\n" % index)
        out.write(body)
        out.write(b"\nendobj\n")
    xref = out.tell()
    out.write(b"xref\n0 %d\n" % (len(objects) + 1))
    out.write(b"0000000000 65535 f \n")
    for offset in offsets:
        out.write(b"%010d 00000 n \n" % offset)
    out.write(b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref))
    return out.getvalue()


def minimal_png() -> bytes:
    """A 1x1 opaque red PNG built with the standard library only."""
    import zlib

    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    idat = zlib.compress(b"\x00\xff\x00\x00", 9)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def stub_ttf() -> bytes:
    """An sfnt offset table with no tables: the `00 01 00 00` signature every TrueType sniffer
    keys on, and nothing a rasteriser could render. A real, renderable font is far too large to
    commit under the 128 KiB cap and too involved to synthesise here; the device check previews a
    real font from a real archive. This member exists so the fixture carries every kind of entry
    the entry cache and the id scheme must handle by name."""
    return struct.pack(">IHHHH", 0x00010000, 0, 0, 0, 0)


def build_sample_entries_zip() -> bytes:
    """One of each: a text file, a PDF, a PNG, a (stub) TTF and a 3 MB zero-filled `.bin` that
    deflates to a few KB -- the entry cache's size-cap and declared-size checks get a member whose
    uncompressed size is far larger than the archive."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(zip_entry("notes.txt"), b"An archive with one of each kind of entry.\n")
        zf.writestr(zip_entry("page.pdf"), minimal_pdf())
        zf.writestr(zip_entry("pixel.png"), minimal_png())
        zf.writestr(zip_entry("font.ttf"), stub_ttf())
        zf.writestr(zip_entry("big.bin"), bytes(3 * 1024 * 1024))
    data = buf.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        assert zf.getinfo("big.bin").file_size == 3 * 1024 * 1024
        assert zf.getinfo("page.pdf").file_size == len(minimal_pdf())
    return data


def build_mixed_links_tar() -> bytes:
    """A regular file, an in-tree symlink to it, a hardlink to it, a fifo, and a directory with a
    file: the entry cache must refuse the symlink and the fifo, resolve the hardlink to its
    target's ordinal, and open the two files."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        tar.addfile(tar_member("target.txt", size=6), io.BytesIO(b"hello\n"))
        symlink = tar_member("link-to-target", mode=0o777)
        symlink.type = tarfile.SYMTYPE
        symlink.linkname = "target.txt"
        tar.addfile(symlink)
        hardlink = tar_member("hard-to-target")
        hardlink.type = tarfile.LNKTYPE
        hardlink.linkname = "target.txt"
        tar.addfile(hardlink)
        fifo = tar_member("fifo", mode=0o644)
        fifo.type = tarfile.FIFOTYPE
        tar.addfile(fifo)
        directory = tar_member("dir", mode=0o755)
        directory.type = tarfile.DIRTYPE
        tar.addfile(directory)
        tar.addfile(tar_member("dir/inner.txt", size=6), io.BytesIO(b"inner\n"))
    return buf.getvalue()


# The raw member names of `messy-paths.tar`, in archive order, with what the tree must make of
# each. Kept as one table so the Rust and Kotlin tests can be checked against it by eye.
MESSY_PATHS: list[tuple[str, bytes | None]] = [
    ("./a", b"a\n"),  # leading ./ stripped -> "a"
    ("dir/", None),  # explicit directory, trailing slash trimmed -> "dir"
    ("dir/x.txt", b"x\n"),
    ("/abs", b"abs\n"),  # leading / stripped -> "abs"
    ("c//d", b"d\n"),  # collapsed -> "c/d", "c" synthesised
    ("dot/./e", b"e\n"),  # "." segment collapsed -> "dot/e"
    ("../escape", b"escape\n"),  # climbs above the root -> quarantined
    ("in/../f", b"f\n"),  # climbs but stays inside -> "f"
    ("dup.txt", b"first\n"),  # the earlier of two members with one path: hidden
    ("dup.txt", b"second\n"),  # last member wins
    ("both", b"file\n"),  # a file ...
    ("both/inside.txt", b"inside\n"),  # ... and a directory of the same name: the directory wins
    ("README", b"upper\n"),  # case differs: two distinct entries
    ("readme", b"lower\n"),
]


def build_messy_paths_tar() -> bytes:
    """Every path shape the tree normalises, on one real tar. The duplicate member is what
    `tar -r` (append) produces; `tarfile` writes it the same way when the name is added twice."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for name, contents in MESSY_PATHS:
            if contents is None:
                directory = tar_member(name, mode=0o755)
                directory.type = tarfile.DIRTYPE
                tar.addfile(directory)
            else:
                tar.addfile(tar_member(name, size=len(contents)), io.BytesIO(contents))
    data = buf.getvalue()
    # Verified, not assumed: tarfile stores every name verbatim (no leading-slash stripping, no
    # normalisation), which is the whole point of this fixture.
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        assert tar.getnames() == [name.rstrip("/") if name.endswith("/") else name for name, _ in MESSY_PATHS] or \
            tar.getnames() == [name for name, _ in MESSY_PATHS], tar.getnames()
    return data


def build_backslash_zip() -> bytes:
    """A ZIP whose member name uses a backslash separator (written by some Windows tools);
    `zipfile` only rewrites `os.sep`, which is `/` on the machine generating this."""
    data = stored_zip([("dir\\file.txt", b"backslash\n"), ("top.txt", b"top\n")])
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        assert zf.namelist() == ["dir\\file.txt", "top.txt"], zf.namelist()
    return data


def build_dot_rooted_tar() -> bytes:
    """The shape `tar -C dir -cf x.tar .` produces: a `./` directory member for the root itself,
    then every member under `./`. The engine drops the root as an entry but it still occupies
    header 0, so the first real member's ordinal is 1 -- the fact the ordinal contract is pinned
    on (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md`, the M3.4-review amendment)."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        root = tar_member("./", mode=0o755)
        root.type = tarfile.DIRTYPE
        tar.addfile(root)
        tar.addfile(tar_member("./first.txt", size=6), io.BytesIO(b"first\n"))
        sub = tar_member("./sub/", mode=0o755)
        sub.type = tarfile.DIRTYPE
        tar.addfile(sub)
        tar.addfile(tar_member("./sub/second.txt", size=7), io.BytesIO(b"second\n"))
    data = buf.getvalue()
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        assert tar.getnames() == ["./", "./first.txt", "./sub/", "./sub/second.txt"] or \
            tar.getnames() == [".", "./first.txt", "./sub", "./sub/second.txt"], tar.getnames()
    return data


def build_damaged_after_3_tar() -> bytes:
    """Three good members, then a header block that is not a tar header (a fixed non-zero byte
    pattern, so it is neither the two zero blocks that mean end-of-archive nor a valid checksum):
    libarchive lists three entries and then fails the fourth header."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for index in range(3):
            body = b"member %d\n" % index
            tar.addfile(tar_member("good-%d.txt" % index, size=len(body)), io.BytesIO(body))
    data = buf.getvalue()
    # Drop tarfile's end-of-archive padding (two zero blocks plus record padding): every byte after
    # the third member's data is zero.
    end = len(data)
    while end > 0 and data[end - 1] == 0:
        end -= 1
    end = (end + 511) // 512 * 512
    garbage = bytes((i * 73 + 29) % 255 + 1 for i in range(1024))
    return data[:end] + garbage


# --------------------------------------------------------------------------------------------


FIXTURES: dict[Path, Callable[[], bytes]] = {
    FIXTURES_DIR / "sample-cd.zip": build_cd_zip,
    FIXTURES_DIR / "sample-streamed.zip": build_streamed_zip,
    FIXTURES_DIR / "sample-copy.7z": build_copy_7z,
    FIXTURES_DIR / "sample-lzma2.7z": build_lzma2_7z,
    FIXTURES_DIR / "sample-encrypted-header.7z": build_encrypted_header_7z,
    FIXTURES_DIR / "sample.iso": build_iso,
    HOSTILE_DIR / "zip-slip.zip": build_zip_slip,
    HOSTILE_DIR / "absolute-path.zip": build_absolute_path_zip,
    HOSTILE_DIR / "symlink-escape.tar": build_symlink_escape_tar,
    HOSTILE_DIR / "many-entries.tar.zst": build_many_entries_tar_zst,
    # M3.3 browsing fixtures.
    FIXTURES_DIR / "nested-depth-4.zip": build_nested_depth_4_zip,
    FIXTURES_DIR / "nested-depth-5.zip": build_nested_depth_5_zip,
    FIXTURES_DIR / "implicit-dirs.zip": build_implicit_dirs_zip,
    FIXTURES_DIR / "sample-entries.zip": build_sample_entries_zip,
    FIXTURES_DIR / "mixed-links.tar": build_mixed_links_tar,
    FIXTURES_DIR / "messy-paths.tar": build_messy_paths_tar,
    FIXTURES_DIR / "backslash.zip": build_backslash_zip,
    FIXTURES_DIR / "dot-rooted.tar": build_dot_rooted_tar,
    FIXTURES_DIR / "damaged-after-3.tar": build_damaged_after_3_tar,
}


def build_all() -> dict[Path, bytes]:
    real_time = time.time
    time.time = lambda: float(FIXED_EPOCH)  # type: ignore[assignment]
    try:
        return {path: build() for path, build in FIXTURES.items()}
    finally:
        time.time = real_time  # type: ignore[assignment]


def main() -> None:
    first = build_all()
    second = build_all()
    for path in FIXTURES:
        assert first[path] == second[path], f"{path.name} is not deterministic across two builds"
        assert len(first[path]) <= MAX_FIXTURE_BYTES, f"{path.name} is {len(first[path])} bytes, over 128 KiB"
    HOSTILE_DIR.mkdir(parents=True, exist_ok=True)
    for path, data in first.items():
        path.write_bytes(data)
        print(f"wrote {path.relative_to(FIXTURES_DIR.parent.parent)} ({len(data)} bytes) sha256 {sha256(data)}")


if __name__ == "__main__":
    main()
