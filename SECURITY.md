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

Permissions and network:

- `MANAGE_EXTERNAL_STORAGE` is requested up front through the system "All files access" screen, with a plain rationale and a working Storage Access Framework fallback if declined (see docs/ARCHITECTURE.md, "Broad storage access"). No legacy `READ/WRITE_EXTERNAL_STORAGE`, no `QUERY_ALL_PACKAGES`.
- `INTERNET` is held for the optional remote providers (SFTP, SMB, WebDAV, S3-compatible) and for BYOK AI connectors. Nothing is transmitted unless the user configures such a connection or explicitly approves an AI transmission preview.
- Cleartext traffic is disabled application-wide; S3 and WebDAV endpoints must be HTTPS, AI connector endpoints must be HTTPS or loopback, and SFTP host keys are pinned on first connection.
- `POST_NOTIFICATIONS` is requested only when a backup plan is given an automatic schedule, for the backup worker's progress notification.

Data at rest:

- Passwords, S3 secret keys and BYOK API keys are stored only in `ApiKeyVault`, encrypted with an Android Keystore key. Remote-connection records themselves hold hosts, ports, usernames and bucket names, never the secret.
- Android backup and device-to-device transfer exclude the vault, the operation journal work directories, backup state and file-history stores (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`).
- The local content index keeps names, metadata and a bounded text sample (at most 4,000 characters) of PDF and Office files the user explicitly chose to index; it never leaves the device.

Components:

- Fylz's own `DocumentsProvider` is exported behind the signature-level `MANAGE_DOCUMENTS` permission; the tool activities are not exported and launcher shortcuts reach them through `MainActivity` actions.
- Archive handling validates extraction paths, rejects symlinks, and enforces entry-count, path-depth, per-file, total-expansion and compression-ratio limits with hostile-fixture tests.
- Previews and text extraction are bounded (input size, page and character caps) so an untrusted file cannot exhaust memory.

Known limitations are tracked in docs/ROADMAP.md; the remaining stable-release gates are listed in README.md.
