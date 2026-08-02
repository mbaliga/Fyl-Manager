# Security and privacy model

## Current guarantees

- No `INTERNET` permission
- No telemetry, analytics or account system
- No blanket storage permission
- Access is limited to document trees selected by the user and grants persisted by Android
- File previews are bounded; document content is not executed
- External opening uses temporary URI grants
- Backups are disabled for the app by default

## Threat model

Fylz handles untrusted filenames, MIME declarations, archives, images, PDFs, remote responses and model output. Treat all of them as hostile input.

Key controls planned or already present:

- never build filesystem paths by concatenating untrusted archive entry names
- verify canonical extraction targets and reject traversal/symlinks that escape the destination
- cap preview bytes, image dimensions, PDF pages and archive expansion ratios
- keep long-running writes in temporary outputs and publish only after successful completion
- avoid logging file names/content in release builds where logs may expose private data
- store provider credentials and API keys with Android Keystore-backed encryption
- redact secrets from crash reports and user-exportable diagnostics
- require explicit per-operation consent before sending content to a cloud model
- sign release artifacts and publish checksums and provenance

## Password archives

Passwords are supplied as `CharArray`, cleared after use and not retained unless a user explicitly opts into encrypted credential storage. Archive encryption parameters must be visible before creation. Legacy ZipCrypto should be offered only for interoperability with a warning; AES-256 is the secure default.

## Responsible disclosure

Do not open a public issue for a vulnerability that exposes user files, credentials or code execution. Use GitHub private vulnerability reporting when enabled. Until then, contact the repository owner privately through the address on their GitHub profile.
