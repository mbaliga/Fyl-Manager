# Preview and Recycle Bin Contract

Status: Binding for v1 and later unless explicitly superseded.

## 1. Broad file preview support

Fylz must preview useful file types even when Android or the installed device has no native handler.

Initial mandatory preview classes:

- Raster images: PNG, JPEG, WebP, BMP, HEIF/HEIC where the platform decoder permits it.
- Animated images: GIF and animated WebP, with play/pause and frame-safe memory limits.
- Vector images: SVG and SVGZ, rendered in-app with scripts, external network references, and unsafe embedded content disabled by default.
- Text and agent-authored files: TXT, Markdown, JSON, JSONL, YAML, TOML, XML, CSV, TSV, source code, logs, diffs, patches, and common configuration files.
- Documents: PDF, EPUB, and other formats for which a safe, maintainable in-app renderer can be provided.
- Audio and video: metadata, artwork or thumbnail, waveform or duration where practical, and playback through supported codecs.
- Archives: browsable contents for supported archive types without extracting first.
- Fonts: specimen preview and metadata.
- Unknown files: hex/text inspection, metadata, MIME information, checksums, and Open with / Share actions.

Preview support must be capability-driven rather than based only on filename extensions. Fylz should inspect MIME type and, where safe and inexpensive, file signatures. A malformed or adversarial file must fail closed and must not crash the browser process.

Large files must use streaming, paging, sampling, or bounded decoding. The preview pane must never load an unbounded file into memory.

## 2. Recycle bin is the default delete path

The normal Delete action means Move to Recycle Bin. It is not a destructive delete.

Required behavior:

1. A delete request moves the item into a Fylz-managed recycle bin when the backing provider permits it.
2. The recycle-bin record stores the original location, original display name, deletion time, source provider, size when known, and enough information to attempt restoration.
3. Restore returns the item to its original location. If that location no longer exists or a name conflict occurs, Fylz asks the user to select a destination or conflict policy.
4. Items in the recycle bin remain there indefinitely. There is no automatic expiry, scheduled cleanup, storage-pressure purge, background deletion, or default retention period.
5. Permanent deletion is manual only and is exposed only inside the recycle-bin interface or through an explicitly labelled advanced action.
6. Empty Recycle Bin is always a deliberate manual action and requires confirmation that clearly states the number of items and known total size.
7. Permanent deletion of one or more selected items also requires explicit confirmation. Undo is not claimed after the provider confirms deletion.
8. Fylz must never silently fall back from Move to Recycle Bin to permanent deletion.

## 3. Provider limitations

Android Storage Access Framework and third-party document providers do not offer a universal system trash API. Fylz therefore uses a managed recycle-bin strategy:

- Prefer an app-managed `.fylz-trash` directory within the same writable storage root so moves can remain local and efficient.
- When an atomic move is unavailable, use a verified copy-then-delete transaction. The source is deleted only after byte count and, where practical, checksum verification succeeds.
- If the provider cannot create or write a recycle-bin location, Fylz reports that the item cannot be recycled. Permanent deletion is not offered as the default fallback.
- Cross-provider recycling must be treated as copy-then-delete and must surface progress, cancellation limits, and partial-failure recovery.

## 4. Safety and auditability

Every recycle, restore, and permanent-delete operation must have an operation record with item identity, source, destination, state, timestamps, and failure details. Logs must not contain file contents or secrets.

Batch operations must be resumable or recoverable after process death where the provider permits it. Partial completion must be shown item by item.

These rules apply equally to local files, removable storage, and third-party providers, subject to their actual capabilities.
