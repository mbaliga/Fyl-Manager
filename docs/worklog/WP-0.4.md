# WP-0.4 — Automated guard for the theme trap

**Status: DONE.**

`guard/ThemeOwnershipGuardTest` scans `app/src/main/java` and fails on any `MaterialTheme`
invocation — `MaterialTheme(` **or** the paren-free trailing-lambda `MaterialTheme {` — outside
the allowed owner. The brace form matters more than the paren form: every Material 3 parameter
except the content lambda has a default, so the habitual shape of the shipped bug is exactly
`MaterialTheme { ... }` (the first cut of this guard matched only the paren and was caught by
adversarial review before merge). Property reads (`MaterialTheme.colorScheme`) never match. A
positive-control test asserts the allowed owner itself matches the pattern, so a dead regex
fails loudly instead of scanning for nothing. It runs as a plain unit test, so it gates both CI
task lists (`testDebugUnitTest` is in `android.yml` and `release-readiness.yml`) with no custom
lint module to maintain — the plan's "cheaper" option, chosen deliberately.

**Deviation from the plan's letter:** the plan names `ui/FylzV1App.kt` as the allowed file, but
the app's one legitimate `MaterialTheme(...)` call lives in `ui/theme/FylzTheme.kt` (the wrapper
`FylzV1App` composes — verified sole call site by grep before writing the guard). The guard
allows exactly that file. The plan's intent — one owner, no bare `MaterialTheme` in Activities —
is enforced as written.

Shared helper `guard/RepoLayout` resolves the repo root by ascending from the test working
directory; it errors rather than skips when it can't (a guard that silently scans nothing
guards nothing). `ToolchainLockstepTest` (WP-0.5) reuses it.
