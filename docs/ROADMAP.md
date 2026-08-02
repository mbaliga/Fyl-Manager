# Roadmap

The order is risk-driven. Core operations and recovery precede optional intelligence.

## 0.1 - Workspace foundation

- SAF tree selection and persisted permission
- tabs and folder history
- adaptive navigation/list/preview shell
- floating preview
- list/grid and three detail densities
- Markdown/agent-text, image and PDF preview
- text/Markdown creation and editing
- folder creation, rename and provider-supported delete
- theme and immersive/traditional modes
- JVM tests, a pinned CI Gradle distribution and Android CI

## 0.2 - Trustworthy operations

- multi-select and selection ranges
- copy, move and duplicate queue
- explicit conflict resolution
- foreground progress and cancellation
- operation history, partial failures and retry
- provider-aware trash/undo
- checksums and duplicate inspection
- reproducible release metadata

## 0.3 - Archive workspace

- browse archives without extracting
- ZIP create/extract
- AES password-protected ZIP
- tar/gzip/xz and 7z coverage
- safe extraction limits and space estimation

## 0.4 - Scan to PDF

- multi-page local capture
- automatic/manual crop and perspective correction
- rotate, reorder, remove and resume interrupted sessions
- colour/greyscale/BW enhancement
- local OCR and searchable PDF option
- direct save into the active folder

## 0.5 - Locations and search

- bookmarks, recents and saved searches
- SMB, SFTP and WebDAV adapters
- indexed search with transparent scope
- tags and virtual collections
- file properties and Linux-aware metadata where available

## 0.6 - Local organization

- deterministic organization rules
- duplicate and near-duplicate views
- downloadable local model manager
- provider-neutral `OrganizerEngine`
- metadata-only/content-bounded scopes
- suggestion provenance and reversible application

## 0.7 - Bring-your-own model providers

- encrypted API key storage
- OpenAI-compatible and explicitly implemented provider adapters
- per-request disclosure and cost/context estimate
- redaction and deny-list controls
- no background upload or silent fallback from local to cloud

## 1.0 gates

- keyboard, mouse, D-pad and screen-reader audit
- large-folder and removable-media stress tests
- interruption/failure test matrix
- archive and parser fuzzing
- signed reproducible release process
- Play permission declaration only if the optional full-access flavor ships
- documented migration and backup behavior
