# Desktop-class audit — does Fylz rival and beat desktop file managers?

**Prepared 23 Aug 2026** against a fresh survey of Windows 11 Explorer (24H2/25H2), macOS
Finder/Quick Look (Tahoe), KDE Dolphin, GNOME Files 49, Total Commander, Directory Opus 13,
and the Android power tier (Solid Explorer, MiXplorer, Material Files, FX, Total Commander
Android). Code claims below were verified against the tree, not the README.

## Verdict, in one paragraph

Fylz already **beats** every desktop manager on exactly one axis, and it is the right axis:
operational safety. A durable journal with process-death recovery, verified moves with
version-evidence cleanup, a real recycle bin, pre-write file history, and conservative retries
is a set no desktop incumbent has — Directory Opus cannot replay a journal, Explorer's undo is
a fragile stack, and none of them verify a move before deleting the source. It also beats
desktop on provider unification (SAF + full filesystem + SFTP/SMB/S3/WebDAV behind one
interface) and on trust (FOSS, no telemetry, while Explorer becomes a Copilot/ads surface).
It does **not yet rival** desktop on: multi-window workflows (dual-pane), a user-facing undo
vocabulary, archive breadth, preview of documents-as-documents, search-as-live-folder
ergonomics, and automation. The claim "rivals AND beats desktop-class" is honest after the
gap list below, not before.

## Scorecard against the desktop-class bar

| Capability (desktop bar) | Fylz today | Standing |
|---|---|---|
| Batch ops with queue, conflict policy, resume | Journal + policies + retry + finish-move | **Beats** (no desktop equivalent of the journal) |
| Undo/redo of file ops | Recycle restore + journal recovery, but no Undo verb | **Behind** — the journal makes real undo BUILDABLE; nobody else can say that |
| Dual-pane | `DualPaneModels.kt` exists; no surface consumes it | **Behind** (models built, unwired — the CapabilityPolicy pattern again) |
| Breadcrumbs + path edit | Breadcrumbs yes; no editable path field | Partial |
| Saved searches / smart folders | Saved searches + smart collections on the index | **At bar**, needs surfacing as live "folders" in the rail |
| Tags/labels | Cross-location tags, tag browser, export/import | **At bar or better** (Explorer has nothing comparable) |
| Checksums | SHA-256 internal (history, backups); user-facing verify in UniversalPreview | Partial — needs create+verify against a manifest (.sfv/sha256sum) |
| Archives | Read: zip/7z/tar/cpio/ar/arj/gz + member extraction; write: zip incl. AES-256. RAR honestly refused; **no zstd read, no 7z/tar write** | Behind TC/MiX and Win11's libarchive on breadth; ahead on safety limits |
| Columns/sorting/grouping | List/grid/details, sort; no per-folder column sets | Partial |
| Network protocols | SFTP/SMB/WebDAV/S3 behind `RemoteProvider` | **At bar+** (S3 exceeds desktop defaults); needs the remote browser promoted from dialog to tab |
| Symlinks/permissions truth | SAF hides them; File backend does not surface them | Behind Material Files/desktop |
| Terminal/scripting/automation | None | Behind — deliberate scope question, not an accident |
| Keyboard | Shortcut policy wired (WP-A3), modifier clicks | Partial until focus traversal (WP-A3b) |

## Preview breadth vs Quick Look (the "preview all file types" ask)

The registry classifies **416 extensions** into families with honest depths (rendered /
structured / inspected), and the bounded inspector means nothing ever fails to open — that
floor is itself Quick-Look-grade behavior. Genuinely rendered today: text/code (large-set),
Markdown, JSON/YAML/TOML, diffs/patches, images incl. SVG/GIF/animated WebP/AVIF/HEIC (via
Coil/platform), PDF with page tools, audio/video metadata + motion thumbnails, fonts
(`FontFilePreview`), archives incl. inside-archive member preview, spreadsheet grids
(XLSX/ODS/CSV via `WorkbookReader`), presentations slide-by-slide
(`PresentationDeckReader`), and 3D wireframes (glTF/OBJ/STL family).

Measured against Finder Quick Look, the missing credibility markers, in order of how often a
real person hits them:

1. **Word-processing text** — `docx/odt/rtf` currently fall into the archive browser or
   inspector; Quick Look renders them. A text-extraction pass over OOXML/ODF XML (the zip
   plumbing already exists in `WorkbookReader`) covers 90% of the need without layout fidelity.
2. **EML/MSG** — absent from the registry entirely (the one class of file the registry does
   not even name). Headers + body text + attachment list is a bounded parser.
3. **EPUB/ebooks** — classified, not rendered; EPUB is zip+XHTML and the pieces exist.
4. **RAW camera files** — classified; preview should use the embedded JPEG fast path.
5. **SQLite/database peek** — classified STRUCTURED; a read-only table listing via the
   platform's own sqlite is cheap and beats Windows outright.
6. **ICS/VCF** — treated as text; a small structured card (who/when/where) matches Quick Look.
7. **RAR read + zstd/tar.zst read** — Win11 reads both natively now; research-gate the
   libraries as ROADMAP already requires (licence/CVE review).
8. `ipynb` — unnamed in the registry; render markdown+code cells as the notebook it is.

## The "beats desktop" plan, ranked

1. **Undo, the verb** (P1): the journal already records everything needed; add inverse
   operations (move back, un-rename, restore-from-recycle) surfaced as one Undo/Redo pair +
   Ctrl+Z. This converts the existing unique asset into the marquee desktop-beating claim:
   "every file operation is reversible" — no desktop manager ships that.
2. **Dual-pane** (P1): wire `DualPaneModels` for the expanded width class (WP-A1's tiers),
   with drag-between-panes and the capability gates already in place.
3. **Preview breadth wave 1** (P1): docx/odt/rtf text, EML, EPUB, RAW-embedded-JPEG, SQLite
   peek, ICS/VCF cards — closes the Quick Look gap where it is actually felt.
4. **Search as a place** (P2): saved searches and smart collections mounted in the locations
   rail as live folders.
5. **Checksum create/verify** (P2): sha256sum/sfv read+write in the selection actions.
6. **Archive breadth** (P2, research-gated): zstd + RAR read via vetted libraries; 7z/tar
   write.
7. **Path edit + per-folder view memory** (P3). Terminal/scripting stays out unless the owner
   wants Fylz to become a platform — that is a doctrine decision, not a checkbox.

Items 1–3 are the difference between "an excellent Android file manager" and the sentence the
owner asked for. All of it slots behind the existing acceptance gates; none of it needs new
architecture.
