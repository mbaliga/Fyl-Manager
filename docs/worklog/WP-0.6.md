# WP-0.6 — Operation-journal crash-injection tests

**Status: DONE — and the suite found a real gap on its first run.**

`operations/OperationJournalCrashInjectionTest` (Robolectric, `@Config(sdk = [35])`) simulates
process death at every journaled transition and asserts recovery lands in completion or a clean,
explained, retry-or-abandon state. Mechanics worth recording:

- **Every `journal.put` is a crash point.** Persistence is a synchronous `commit()`, including
  the per-buffer-chunk progress writes during transfers, so seeding the journal via its own API
  and then overwriting the `process_session` token with a foreign value reproduces exactly the
  on-disk state + code path of a post-crash construction. No emulator, no JVM-killing.
- Coverage: the interruption mapping at each pre-terminal transition per operation type
  (COPY/MOVE/RECYCLE/RESTORE/PERMANENT_DELETE/RENAME/ARCHIVE/EXTRACT); terminal states left
  untouched; finished/skipped items inside an interrupted operation preserved exactly;
  never-repeated-copies (retry plan excludes SUCCEEDED items; a MOVE with
  `MOVE_SOURCE_DELETE_PENDING` routes to cleanup, and a mixed batch refuses one-batch retry);
  never-silently-forgotten (`clearFinished` keeps NEEDS_ATTENTION); once-per-process recovery;
  codec round-trip; corrupted-payload behavior.

**The gap it found:** `OperationRecoveryPolicy.interruptedStates` omitted `QUEUED`, but the
transfer path's *first durable write* is a PREFLIGHT operation whose items are QUEUED. A crash
there recovered the operation to NEEDS_ATTENTION while its items stayed QUEUED with no error
code — "still waiting" inside an operation that would never run. Fixed by adding QUEUED to the
set (a queued anything inside a crashed process was interrupted before it began); pinned at the
item level in the crash suite and at the operation level in `OperationRecoveryPolicyTest`.

**Known contract, deliberately pinned not fixed:** a corrupted journal payload decodes to an
empty list — silent drop. `RecycleBinStore` keeps a last-known-good backup; the journal doesn't
yet. Hardening candidate for the Phase 1 engine extraction (WP-1.4 versioning work), not for a
test WP.

**Next:** WP-1.4 must keep this suite green unchanged across the `core-operations` extraction.
