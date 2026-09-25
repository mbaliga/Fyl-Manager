#!/usr/bin/env python3
"""Generates the archive-format fixtures under `core/fixtures/archives/` that `fylz-archive`'s
M3.2 tests read (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section 2.7): the ZIP, 7-Zip and ISO 9660
files whose listing or data needs a *seek*, so the tests can show what a seekable descriptor buys
over a pipe, plus the hostile files the extraction policy must refuse end to end.

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
