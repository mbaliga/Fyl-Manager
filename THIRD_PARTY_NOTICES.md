# Third-party notices

Fylz is licensed under Apache License 2.0. The application depends on third-party software and platform services with their own licences or terms.

This file records the direct dependencies declared by the current Android project. A generated `releaseRuntimeClasspath` report is also produced by the release-readiness workflow and must be reviewed before publishing a stable release because transitive dependencies can change.

## Apache License 2.0

The following direct dependencies are distributed under Apache License 2.0:

- Kotlin standard library
- AndroidX Activity, Core, Compose, Material, Lifecycle, DocumentFile and WorkManager libraries
- Material Icons Extended
- Coil Compose, GIF and SVG modules
- OkHttp
- zip4j
- AndroidX Test, Espresso and Compose UI test libraries

Copyright notices and licence texts for packaged dependencies are preserved according to their respective distribution requirements. Fylz's packaging configuration removes duplicate `META-INF` licence resources from the APK; it does not change the underlying licence obligations.

## Google platform SDK terms

- Google Play services ML Kit Document Scanner is a Google-distributed SDK and is governed by the applicable Google APIs and SDK terms rather than the Fylz Apache licence.

The scanner is used only when the user explicitly starts a scan. Availability depends on the device and Google Play services.

## Test-only dependency

- JUnit 4.13.2 — Eclipse Public License 1.0

## Release review procedure

Before each public release:

1. Run the `Release readiness` GitHub Actions workflow.
2. Download and inspect `release-runtime-dependencies`.
3. Compare the generated dependency graph with this file.
4. Review new or upgraded dependencies for licence compatibility, security advisories and provenance.
5. Update this notice when direct dependencies or material transitive obligations change.

No private Fonebrew Studio package, asset, model, credential or internal dependency may be included in the public Fylz build.
