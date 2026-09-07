# WP-0.7 — Provider contract test suite (skeleton)

**Status: DONE — 42 tests across the suite, every behavior exercised for real on at least one
backend.**

`storage/StorageBackendContractTest` is the abstract behavioral gate; `BackendFixture` is what a
backend supplies to enter it. Two concrete fixtures today:

- **`FileBackendContractTest`** — the real `FylzFilesDocumentsProvider` over a temp dir
  (Robolectric `ShadowEnvironment` anchors the primary volume; provider registered with the
  `MANAGE_DOCUMENTS`-protected `ProviderInfo` that `DocumentsProvider.attachInfo` demands).
- **`FakeSafBackendContractTest`** — `FakeSafDocumentsProvider`, an in-memory node-tree
  DocumentsProvider whose knobs express what the File backend can't on a JVM host:
  case-insensitive names (the sdcardfs trap), null `LAST_MODIFIED`, cross-tree move refusal.
  Bytes live in opaque blob files so host-filesystem name semantics can't leak into the fake.

Covered per the plan's list: create/list/rename/move/delete/recursive-delete semantics,
duplicate-name disambiguation, rename/move-onto-existing failures, case-only rename (both
directions, knob-gated), NFC/NFD coexistence without normalization, emoji/RTL names,
path-separator containment (the reserved-character shape that matters: a `../a/b.txt` display
name must yield a direct child or a clean refusal, never traversal), 300-char names (truncation
pinned), zero-byte files, >2 GiB size reporting (sparse-seeded, File backend), zero-epoch and
null timestamps, read-only flags (self-probing — `setWritable(false)` is a no-op for root, so
the test gates rather than lies), cursor-snapshot stability + requery convergence under
out-of-band insert/delete, and a pin that provider-level `copyDocument` stays refused — copying
is the operation engine's job, and a backend that grows native copy must arrive as a capability
knob, not a second unannounced copy path.

Declared out of this suite rather than silently absent: **trash** is client-side composition
(`RecycleBinService` + `.fylz-trash`), exercised by its own service tests and WP-0.6's crash
suite, so it is not a provider-contract behavior today; **crash-at-journaled-steps** lives in
WP-0.6's suite; other literal reserved characters (`:`, `*`, `?`) ride the File backend's
sanitizer unpinned — behavior to define, then pin, when Phase 1 touches the provider.

Also `FileStorageProviderRootGroupsTest`: `rootGroups` grouping and standard-directory
filtering, using a local `Environment.isExternalStorageManager` shadow (Robolectric 4.16.1
doesn't provide one) — kept to its own class so the shadow can't leak into contract runs.

**Findings worth keeping:**

- **Harness, not provider:** the JVM decodes directory names with the host locale, so a
  C/POSIX-locale machine read UTF-8 names back as `?` and failed the unicode tests for reasons
  no provider caused. Fixed in `app/build.gradle.kts` `testOptions`: unit-test JVMs run with
  `LANG=LC_ALL=C.UTF-8` — Android itself is always UTF-8, so the harness now matches the
  platform under test on any host.
- **Real File-backend bug, documented not pinned:** `sanitizeDisplayName` truncates to 255
  UTF-16 units, so a 255-emoji name still exceeds ext4's 255-byte limit and creation fails.
  The suite deliberately does not encode that as expected behavior; fix candidate for Phase 1
  provider work.
- The legacy five-argument `resolver.query` overload lands in `DocumentsProvider`'s
  "Pre-Android-O query format" rejection under Robolectric; the suite uses the Bundle overload
  throughout. `DocumentRepository.listChildren` uses the five-argument form in production —
  device-equivalent, but it means that method can't be driven under Robolectric until it
  switches overloads (note for Phase 1 extraction).

**Next:** every future provider (the existing `network/` ones included, when they grow a
DocumentsProvider-shaped adapter in later phases) enters by writing a `BackendFixture` and
passing this suite unchanged — acceptance law #8 made mechanical.
