# Phase 0 exit note

**Claude-side work: COMPLETE (9 Aug 2026).** Exit criteria from the plan, checked:

| Criterion | State |
|---|---|
| CI runs on push | ✅ both workflows ran green on PR #14 (WP-0.1 resolved itself; see its worklog) |
| CI runs on manual dispatch | ✅ `workflow_dispatch` on both workflows (WP-0.2) |
| PR #13 merged | ✅ merged before this session (WP-0.3) |
| Theme guard automated | ✅ `ThemeOwnershipGuardTest`, gates both task lists (WP-0.4) |
| Kotlin lockstep closed + codified | ✅ 2.1.0 everywhere; `ToolchainLockstepTest` fails drift (WP-0.5a) |
| Crash-injection suite | ✅ `OperationJournalCrashInjectionTest`; found+fixed the QUEUED-item recovery gap (WP-0.6) |
| Provider contract suite | ✅ abstract gate + File & fake-SAF fixtures, 42 tests (WP-0.7) |
| Device-test script prepared | ✅ `docs/DEVICE-TEST-BUILD3.md` (WP-0.8) |

**Remaining before Phase 1 starts (plan §7, session 3 depends on "Phase 0 green"):**

1. **Owner:** run `docs/DEVICE-TEST-BUILD3.md` on hardware; findings file into Phase 2
   (WP-2.1 especially). The plan's own gate for Phase 2, not Phase 1 — Phase 1 (identity,
   capabilities, extraction) can start once this PR's CI is green.
2. Merge the open PR (merge commit, not squash — submodule pins in play).

**Verification:** `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and
`:app:lintRelease` green locally against SDK 36 / Gradle 8.14.3, plus green CI on the branch.
