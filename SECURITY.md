# Security policy

Fylz handles untrusted files and, in future, credentials for optional providers. Security reports are welcome.

## Supported versions

Until the first stable release, only the latest code on `main` and the latest tagged pre-release are supported.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability or data-loss condition.

Use GitHub's private vulnerability reporting feature for this repository when enabled. If it is not available, contact the repository owner through the private contact channel listed on their GitHub profile and include “Fylz security” in the subject.

Please provide:

- affected commit/version and Android version/device;
- provider/storage configuration;
- minimal reproduction steps or a proof-of-concept using non-sensitive fixtures;
- expected and observed behavior;
- potential confidentiality, integrity, availability, or data-loss impact;
- whether the issue is already public or under active exploitation;
- suggested mitigation, if known.

Do not attach real personal documents, API keys, passwords, signing material, or private Fonebrew content.

## Priority areas

Reports are especially valuable for:

- unintended file disclosure or provider-boundary bypass;
- path traversal, symlink, overwrite, or decompression-bomb issues;
- arbitrary code execution through previews, archives, PDFs, models, or themes;
- unsafe rename/copy/move/delete behavior or irrecoverable data loss;
- credential leakage from optional BYOK connectors;
- model/package signature or update-chain bypass;
- cleartext network use or sending content without explicit approval;
- backup/export leakage;
- intent/URI permission confusion or confused-deputy behavior;
- logs, crash reports, analytics, or screenshots containing file content or secrets.

## Coordinated disclosure

Maintainers will acknowledge a complete report, assess impact, work on a fix, and coordinate disclosure based on severity and exploitability. No fixed response SLA is promised during the pre-release stage, but credible reports will be handled in good faith.

## Current security posture

The foundation:

- uses SAF-scoped, user-selected URI permissions;
- requests no network, legacy external-storage, or all-files permission;
- disables cleartext traffic;
- bounds initial text previews;
- excludes sensitive work directories from Android backup;
- validates ZIP extraction paths and rejects extracted symlinks;
- stores no model/API credentials because remote connectors are not yet implemented.

The current archive service is staged, not declared production-ready. Additional size/ratio/count limits, fuzz/malformed fixtures, cancellation tests, and password-lifetime hardening are required before the archive UI is released.
