# WP-0.4 — Automated guard for the theme trap

**Status: DONE.**

`guard/ThemeOwnershipGuardTest` scans `app/src/main/java` and fails on any `MaterialTheme(`
invocation outside the allowed owner. Property reads (`MaterialTheme.colorScheme`) don't match;
only the invocation is claimed. It runs as a plain unit test, so it gates both CI task lists
(`testDebugUnitTest` is in `android.yml` and `release-readiness.yml`) with no custom lint module
to maintain — the plan's "cheaper" option, chosen deliberately.

**Deviation from the plan's letter:** the plan names `ui/FylzV1App.kt` as the allowed file, but
the app's one legitimate `MaterialTheme(...)` call lives in `ui/theme/FylzTheme.kt` (the wrapper
`FylzV1App` composes — verified sole call site by grep before writing the guard). The guard
allows exactly that file. The plan's intent — one owner, no bare `MaterialTheme` in Activities —
is enforced as written.

Shared helper `guard/RepoLayout` resolves the repo root by ascending from the test working
directory; it errors rather than skips when it can't (a guard that silently scans nothing
guards nothing). `ToolchainLockstepTest` (WP-0.5) reuses it.
