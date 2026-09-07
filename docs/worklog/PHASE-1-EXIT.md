# Phase 1 exit note

**Claude-side work: COMPLETE (12 Aug 2026).** Exit criteria from the plan, checked:

| Criterion | State |
|---|---|
| Opaque item identity (`ItemRef`/`ItemSnapshot`) | ✅ `core-model`; `Uri`↔`ItemRef` adapter; journal + low-risk call sites migrated (WP-1.1) |
| Expanded capability vocabulary | ✅ `core-model.ItemCapability`; both providers remapped; `core-vfs.CapabilityPolicy` built, unconsulted by design (WP-1.2) |
| Module extraction | ✅ `core-model`, `core-vfs`, `core-operations`, `core-format`; zero `android.*` imports; `core-index`/`core-history` untouched as instructed (WP-1.3) |
| Journal serialization versioning | ✅ `JournalSchema.CURRENT_VERSION = 2`; shape-based decode; WP-0.6 crash suite passes unchanged (WP-1.4) |
| "Android app uses the extracted contracts" | ✅ every call site that constructed/read `OperationItem`/`FileOperation`/the old capability enum now goes through the new modules |
| "Full test suite green" | ✅ 306 tests: 11 `core-model` + 6 `core-format` + 7 `core-vfs` + 17 `core-operations` + 265 `app`, all passing |
| "Zero functional regression (WP-0.7 suite before/after)" | ✅ `StorageBackendContractTest` and its two fixtures pass unchanged; no behavior of either provider changed, only its capability vocabulary's names |

**Verification:** `:core-model:test :core-format:test :core-vfs:test :core-operations:test
:app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease`
all green locally against SDK 36 / Gradle 8.14.3.

**Deliberately deferred, not gaps:**

- `ItemIdentity.compareVersions` is built and tested but not wired into
  `FileOperationService.finishMoveCleanup` — wiring it changes today's "unreadable size implies
  proceed" behavior, a real decision for whoever consumes it, not a mechanical migration.
- `core-vfs.CapabilityPolicy` has no caller — commands declaring required capabilities and the
  UI deriving visibility from them is Phase 2's job per the plan, not this phase's.
- `RecycleRecord`'s own persisted `Uri` fields, and `core-format`'s six-level preview vocabulary
  becoming a real editor pipeline, are both explicitly out of scope for the work packages this
  phase named.

**Next:** Phase 2 (per the plan's own sequencing) begins from here — capability-gated command
visibility, and whatever device-acceptance findings come out of `docs/DEVICE-TEST-BUILD3.md`
once it's run on hardware.
