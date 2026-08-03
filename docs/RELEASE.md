# Fylz release procedure

This document separates automated release-candidate validation from the steps that require a maintainer-controlled signing key and Android devices.

## Automated candidate validation

Run the GitHub Actions workflow **Release readiness** from the Actions tab, or push a version tag matching `v*`.

The workflow performs:

- JVM unit tests;
- release lint;
- R8/resource-shrunk release assembly;
- generation of the `releaseRuntimeClasspath` dependency graph;
- upload of the unsigned release APK, lint report and dependency report.

An unsigned APK is a build-validation artifact only. It is not a distributable production release.

## Signing-key requirements

Create and retain the production keystore outside the repository. Never commit:

- `.jks` or `.keystore` files;
- store or key passwords;
- `keystore.properties`;
- base64-encoded signing material;
- private Fonebrew or organization credentials.

Use a unique Fylz upload key and protect it through the maintainer's secret-management system. Record the certificate fingerprint in private release records.

## Signed candidate

A maintainer should sign the validated release output with Android's supported signing tooling or through the intended app-store pipeline. Verify the signed artifact with:

```bash
apksigner verify --verbose --print-certs app-release.apk
```

Record:

- commit SHA and version;
- SHA-256 of the signed APK or bundle;
- signing-certificate SHA-256 fingerprint;
- release-readiness workflow run;
- dependency and lint artifacts reviewed;
- devices/providers used for acceptance.

## Upgrade acceptance

Test both a clean install and an in-place upgrade from the previous published candidate.

The upgrade must preserve:

- persisted SAF grants where Android/provider policy permits;
- backup plans, snapshots and run history;
- file-history settings and retained versions;
- recycle-bin and operation-journal records;
- favourites, tags and saved searches;
- encrypted credential records without exposing their values.

The upgrade must not:

- silently delete external backup snapshots;
- reset scheduled work without reconciling it;
- migrate private metadata into Android cloud backup;
- weaken cleartext-network or exported-component defaults;
- log file contents, passwords, API keys or signing data.

## Versioning and notes

Before tagging:

1. Update `versionCode` and `versionName`.
2. Update `CHANGELOG.md` with implemented behavior, migrations, security changes and known limitations.
3. Update `THIRD_PARTY_NOTICES.md` after reviewing the generated dependency graph.
4. Confirm `README.md`, `SECURITY.md`, architecture and roadmap status are current.
5. Link the device/provider acceptance record.

## Rollback

Retain the prior signed release and its release record. If a candidate introduces data loss, provider-boundary failure, credential exposure or an unsafe migration, stop rollout and restore the prior release while preserving evidence needed to reproduce the fault.
