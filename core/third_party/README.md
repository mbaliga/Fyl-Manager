# Third-party sources

Vendored C sources land here as git submodules, one per library, each carrying its
own licence file, built by `core/crates/fylz-archive/build.rs` outside cargo (see
`docs/agent/MASTER_PLAN.md` sections 2.2 and 4.2) and recorded in the root
`THIRD_PARTY_NOTICES.md`. Compression backends are added one at a time, in the order
`docs/agent/DESIGN-M31-COMPRESSION-LIBS.md` section 4 gives; 7-Zip and the libyal
libraries follow later in M3/M4.

| Directory | Project | Pinned tag (commit) | Licence | Notice file |
|---|---|---|---|---|
| `libarchive/` | libarchive | `v3.8.9` (`27cbc782`) | BSD-2-Clause (with the exceptions listed in `THIRD_PARTY_NOTICES.md`) | `libarchive/COPYING` |
| `lz4/` | lz4 (only `lib/` is compiled) | `v1.10.0` (`ebb370ca`) | BSD-2-Clause | `lz4/lib/LICENSE` |
| `zstd/` | Zstandard (only `lib/` is compiled) | `v1.5.7` (`f8745da6`) | BSD-3-Clause, elected from the BSD-3-Clause OR GPL-2.0 dual licence (see `THIRD_PARTY_NOTICES.md`) | `zstd/LICENSE` |
