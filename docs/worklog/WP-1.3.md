# WP-1.3 — Module extraction

**Status: DONE for the four named modules. `core-index`/`core-history` intentionally not
touched, per the plan's explicit instruction not to extract them yet.**

Four new pure-JVM Gradle modules, wired into the composite via `settings.gradle.kts` and pulled
into `app/build.gradle.kts` as project dependencies. Each targets `jvmToolchain(17)` with only
the `org.jetbrains.kotlin.jvm` plugin — no `android.*` import compiles in any of them, checked
manually per module during migration and confirmed by every module's test suite running as
plain JVM `test` tasks (`:core-model:test`, `:core-format:test`, `:core-vfs:test`,
`:core-operations:test`), not `testDebugUnitTest`.

Dependency shape matches the plan exactly: `core-model` depends on nothing else in the set;
`core-vfs`, `core-operations`, and `core-format` each depend only on `core-model`, never on one
another.

- **`core-model`** — `ItemRef`, `ItemSnapshot`, `VersionStamp`, `ItemCapability`,
  `ItemIdentity`, `EntryKind` (moved verbatim from `app/model/Models.kt`). The load-bearing
  identity types WP-1.1 and WP-1.2 build on.
- **`core-vfs`** — `CapabilityPolicy`/`UserAction`/`CapabilityDecision`: what a user-facing
  action requires in `ItemCapability` terms, and whether an offered set satisfies it. Built,
  tested, unconsulted — Phase 2's job to wire in.
- **`core-operations`** — `FileOperationType`, `OperationState`, `ConflictPolicy`,
  `OperationItem`, `FileOperation`, `OperationRecoveryPolicy`, `OperationRetryPolicy`/
  `OperationRetryPlan`, `JournalSchema`. The operation-journal model itself, now `ItemRef`-typed
  per WP-1.1, plus every policy that only ever needed to reason about that model's shape — none
  of them touched `android.net.Uri` even before this phase, so extracting them was a package
  move, not a rewrite of their logic.
- **`core-format`** — `FileFormatRegistry`, `PreviewFamily`, `PreviewDepth`,
  `FileFormatDescriptor` (moved from `app/preview/FileFormatRegistry.kt`, `EntryKind` import
  repointed at `core-model`), plus a new `PreviewLevel` enum
  (`IDENTITY`/`METADATA`/`STRUCTURED`/`INSPECTED`/`RENDERED`/`EDITABLE`) and a
  `FileFormatDescriptor.provisionalLevel` extension mapping today's three `PreviewDepth` values
  onto three of the six levels. Documented as provisional scaffolding: the plan's six-level
  preview/edit vocabulary is WP-3.6's job, and this phase only needed a name to grow into, not
  a working editor pipeline.

## The one deliberate exception: `org.json` stays in `app`

`core-operations` never takes an `org.json` dependency, even though its own model
(`OperationItem`/`FileOperation`) is exactly what the journal serializes. The actual JSON codec
stays in `app/operations/OperationJournal.kt`. `org.json.*` is part of the Android platform
classpath at runtime, not something available (or safe to bundle a second copy of) on a
pure-JVM module — a `core-operations` module that pulled in a Maven `org.json` artifact and got
packaged into the APK would risk shadowing or conflicting with the platform's own copy. Every
`core-operations`/`core-format`/`core-vfs`/`core-model` `build.gradle.kts` documents this
directly for whoever reaches for `org.json` next in one of these modules.

## Call-site migration

Every `app` file that referenced a moved type from its old `io.github.mbaliga.fylz.operations`/
`io.github.mbaliga.fylz.preview` package location had its import repointed at the new
`io.github.mbaliga.fylz.core.*` package (`FileOperationService`, `RecycleBinService`,
`FileTools`, `ArchiveService`, `OperationJournal`, `OperationHistoryActivity`,
`OperationHistoryDialog`, `FylzAppShell`, the six preview-consuming Compose files, and their
tests). The old `app/operations/OperationModels.kt` and `OperationRetryPolicy.kt` — now fully
superseded — and their app-side tests were deleted rather than left as dead re-exports.
