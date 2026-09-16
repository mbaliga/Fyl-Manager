# Contributing to Fylz

Thank you for helping build a small, trustworthy Android file workspace.

## Before opening code

For material features, start with an issue that states:

- the user problem and complete journey;
- Android versions/form factors/providers affected;
- permissions and data touched;
- failure, cancellation, collision, and recovery behavior;
- accessibility and keyboard/mouse considerations;
- security and privacy boundaries;
- tests and evidence required.

Feature count is not the goal. A smaller reliable workflow is preferred over a broad mock implementation.

## Development setup

- JDK 17
- Android SDK: `platforms;android-36` and `build-tools;36.0.0` (the app targets and compiles against API 36; minSdk is 31)
- Gradle 8.14.3 — use the wrapper (`./gradlew`), which provisions it
- The `hyle-design-system` and `shared-libraries` git submodules must be initialized: `git submodule update --init --recursive`

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :core-model:test :core-format:test :core-vfs:test :core-operations:test
```

CI runs the same core checks and uploads a debug APK; the Release readiness workflow adds release lint, R8 assembly and an unsigned App Bundle.

## Branch and pull request guidance

- Keep a change focused.
- Use descriptive commits.
- Include screenshots or recordings for visible changes across relevant window sizes.
- State what is implemented and what remains mocked/staged.
- Add tests for behavior, regressions, parsers, path handling, and operation failures.
- Do not weaken storage, network, backup, or cleartext-traffic defaults merely to simplify a demo.

## Architecture expectations

- File operations are deterministic. AI may propose actions but never bypass validation and approval.
- Treat document URIs as provider-owned identifiers, not filesystem paths.
- Discover and respect provider capabilities.
- Bound reads, previews, indexing, and extraction.
- Destructive operations must be explicit and recoverable where technically possible.
- Long-running operations need progress, cancellation, cleanup, and process-death behavior.
- New network/model integrations must be optional modules and disclose data flow.

## Open-source and provenance rules

- Do not contribute private Fonebrew/Studio code, assets, screenshots, secrets, or internal documentation.
- Do not copy source from another file manager merely because it is open source; preserve license compatibility and attribution.
- Record the origin and license of third-party assets, models, fixtures, and significant code adaptations.
- Generated code is still the contributor's responsibility. Review it for licenses, correctness, security, and maintainability.
- Test fixtures must not contain real user documents or credentials.

## Style

- Kotlin official style.
- Compose UI should expose semantics/content descriptions and visible focus.
- Prefer semantic design tokens over hard-coded presentation values.
- Keep user-facing language factual and calm.
- Avoid unsupported parity, privacy, security, or “on-device” claims.

## Security-sensitive changes

Archive parsers/extraction, PDF/document parsers, broad storage access, remote connectors, credential handling, model download/loading, and destructive file operations require explicit security review. Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md), not in a public issue.
