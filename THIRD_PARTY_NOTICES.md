# Third-party notices

Fylz is licensed under Apache License 2.0. The application depends on third-party software and platform services with their own licences or terms.

This notice is reconciled against the generated `releaseRuntimeClasspath` graph produced by the Release readiness workflow. The graph remains the authoritative inventory for a specific build because Gradle may resolve or upgrade transitive modules over time.

## Apache License 2.0 families

The resolved release graph contains the following open-source families distributed under Apache License 2.0:

- Kotlin standard library, Kotlin coroutines and Kotlin serialization
- JetBrains annotations and JetBrains Compose/AndroidX compatibility modules
- AndroidX Activity, Annotation, AppCompat, Architecture, Collection, Compose, Core, DocumentFile, Lifecycle, Room, SavedState, SQLite, Startup, WorkManager and their transitive AndroidX modules
- AndroidSVG
- Accompanist Drawable Painter
- Coil
- OkHttp and Okio
- zip4j
- JSpecify annotations
- `javax.inject`
- Guava `listenablefuture`
- JNA (`net.java.dev.jna:jna`) — dual-licensed Apache-2.0/LGPL-2.1; the Apache-2.0 option applies
  here (M2.2, for the generated `fylz-core` uniffi bindings)
- Kotlin coroutines Android (`org.jetbrains.kotlinx:kotlinx-coroutines-android`, M2.2)

The exact artifacts and versions are available in the `release-runtime-dependencies` workflow artifact.

Copyright notices and licence texts must be preserved according to each project's distribution requirements. Fylz's packaging configuration removes duplicate `META-INF` licence resources from the APK to avoid Android packaging collisions; that technical exclusion does not change or waive any licence obligation.

## Google-distributed SDKs and services

The resolved graph also contains Google-distributed components pulled by the document scanner integration, including:

- Google Play services base, basement and tasks
- Google Play services ML Kit Document Scanner
- ML Kit common components
- Firebase component/encoder annotations and runtime components
- Google Data Transport runtime components

These artifacts are governed by the applicable Google APIs, SDK and service terms and any notices shipped with the artifacts. They are not relicensed by the Fylz Apache licence.

The scanner is invoked only after an explicit user action. Availability and implementation can depend on the device and Google Play services.

## Rust core (`core/`)

The `core/` Rust workspace's own dependency graph is tracked separately by `core/deny.toml`
(cargo-deny), which enforces the licence allow-list in `docs/agent/MASTER_PLAN.md` section 2.2.
Notable dependencies as of M2.2:

- uniffi 0.32.2 (`core/crates/fylz-ffi-android`) — MPL-2.0, file-level copyleft, permitted
  anywhere per section 2.2. Generates the Kotlin bindings in
  `app/src/main/java/io/github/mbaliga/fylz/core/`.

## Test-only dependency

- JUnit 4.13.2 — Eclipse Public License 1.0

## Release review procedure

Before each public release:

1. Run the `Release readiness` GitHub Actions workflow on the exact release commit.
2. Download and inspect `release-runtime-dependencies`.
3. Compare the generated graph with this notice and the prior release graph.
4. Review every new or upgraded family for licence compatibility, security advisories, provenance and maintenance status.
5. Review Google-distributed SDK terms when their artifacts or intended use change.
6. Update this notice when direct dependencies or material transitive obligations change.
7. Store the reviewed dependency graph with the release record.

No private Fonebrew Studio package, asset, model, credential, signing material or internal dependency may be included in the public Fylz build.
