#!/usr/bin/env python3
"""Generates the compression-filter fixtures under `core/fixtures/` that `fylz-archive`'s Rust
tests read (per `docs/agent/MASTER_PLAN.md` section 3.4): one valid and one hostile (truncated)
archive per libarchive compression filter this crate turns on, so `cargo test` never needs any
compression tool installed.

Every entry's contents match `fylz-archive/src/lib.rs`'s own `ar`-format fixture (`hello.txt`/
`second.txt`), so every format's fixture in this repo is proven against the same well-known pair
of files.

Needs the Python `lz4` package for the lz4 fixtures, and/or `zstandard` for the zstd fixtures
(`pip install --user lz4 zstandard`; PyPI is reachable in this project's build environment even
when the `lz4`/`zstd` CLIs are not installed). Fails loudly naming exactly what to install rather
than silently skipping a format. The zlib fixtures (gzip, and a ZIP with deflated entries) need
only the standard library (`gzip`, `zipfile`).

Usage: python3 tools/fixtures/make_compression_fixtures.py [lz4] [zstd] [zlib]
  With no arguments, generates every format this script knows about.
"""

from __future__ import annotations

import gzip
import io
import sys
import tarfile
import zipfile
import zlib
from pathlib import Path
from typing import Callable

FIXTURES_DIR = Path(__file__).resolve().parent.parent.parent / "core" / "fixtures"

# Matches fylz-archive/src/lib.rs's own `build_ar_fixture` test helper -- one well-known pair of
# files, reused across every format's fixture.
ENTRIES: list[tuple[str, bytes]] = [
    ("hello.txt", b"hello world"),
    ("second.txt", b"more data, a bit longer"),
]


def build_tar() -> bytes:
    """A plain, uncompressed tar with ENTRIES -- byte-correct because it's built by Python's own
    stdlib `tarfile`, not hand-encoded."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        for name, contents in ENTRIES:
            info = tarfile.TarInfo(name=name)
            info.size = len(contents)
            tar.addfile(info, io.BytesIO(contents))
    return buf.getvalue()


def write(path: Path, data: bytes) -> None:
    path.write_bytes(data)
    print(f"wrote {path} ({len(data)} bytes)")


def generate_lz4(tar_bytes: bytes) -> None:
    try:
        import lz4.frame
    except ImportError as exc:
        raise SystemExit(
            "make_compression_fixtures.py needs the Python `lz4` package for the lz4 fixtures "
            "-- install it with `pip install --user lz4`."
        ) from exc

    compressed = lz4.frame.compress(tar_bytes)
    write(FIXTURES_DIR / "sample.tar.lz4", compressed)
    # Hostile: a valid LZ4 frame (magic number intact, so libarchive's filter still bids and
    # accepts it) cut off partway through its compressed block. libarchive must report this as a
    # fatal error while reading entries/data, never panic or silently return a short read.
    write(FIXTURES_DIR / "truncated.tar.lz4", compressed[: len(compressed) // 2])


def generate_zstd(tar_bytes: bytes) -> None:
    try:
        import zstandard
    except ImportError as exc:
        raise SystemExit(
            "make_compression_fixtures.py needs the Python `zstandard` package for the zstd "
            "fixtures -- install it with `pip install --user zstandard`."
        ) from exc

    compressed = zstandard.ZstdCompressor().compress(tar_bytes)
    write(FIXTURES_DIR / "sample.tar.zst", compressed)
    # Hostile, same shape as the lz4 case above: the zstd magic number survives the cut, so the
    # filter still bids, but the frame's compressed block is incomplete.
    write(FIXTURES_DIR / "truncated.tar.zst", compressed[: len(compressed) // 2])


def generate_zlib(tar_bytes: bytes) -> None:
    # mtime=0: the gzip header otherwise embeds the current time, and these bytes are committed.
    compressed = gzip.compress(tar_bytes, mtime=0)
    write(FIXTURES_DIR / "sample.tar.gz", compressed)
    # Hostile, same shape as the lz4/zstd cases: the gzip magic and header survive the cut, so the
    # filter still bids, but the deflate stream ends mid-block. Unlike lz4/zstd, deflate decodes
    # incrementally, so the cut point matters: at half, the stream has yielded fewer than 512
    # bytes, i.e. not even the first tar header is complete, so `entries` and `read_entry` alike
    # must fail (checked below rather than assumed, since a change to ENTRIES would move it).
    truncated = compressed[: len(compressed) // 2]
    decoded = zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(truncated)
    assert len(decoded) < 512, f"truncated.tar.gz decodes {len(decoded)} bytes, a whole tar header"
    write(FIXTURES_DIR / "truncated.tar.gz", truncated)
    # A ZIP whose entries use method 8 (deflate): libarchive's ZIP reader can only decode those
    # when built with zlib (`archive_read_support_format_zip.c` is `#ifdef HAVE_ZLIB_H`), which
    # is the highest-value thing zlib unlocks. A fixed DOS timestamp and explicit Unix mode keep
    # the bytes deterministic. `writestr` with ZIP_DEFLATED always records method 8, even for
    # entries this short where deflate does not actually save bytes; asserted below.
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, contents in ENTRIES:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, contents)
    with zipfile.ZipFile(io.BytesIO(buf.getvalue())) as zf:
        methods = {i.filename: i.compress_type for i in zf.infolist()}
    assert all(m == zipfile.ZIP_DEFLATED for m in methods.values()), methods
    write(FIXTURES_DIR / "sample-deflate.zip", buf.getvalue())


GENERATORS: dict[str, Callable[[bytes], None]] = {
    "lz4": generate_lz4,
    "zstd": generate_zstd,
    "zlib": generate_zlib,
}


def main(argv: list[str]) -> None:
    formats = argv or list(GENERATORS)
    unknown = [f for f in formats if f not in GENERATORS]
    if unknown:
        raise SystemExit(f"unknown format(s): {unknown}; known formats: {list(GENERATORS)}")
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    tar_bytes = build_tar()
    for fmt in formats:
        GENERATORS[fmt](tar_bytes)


if __name__ == "__main__":
    main(sys.argv[1:])
