# Constellation contract — how Fylz complements the other cells

**Owner direction (23 Aug 2026).** The apps complement each other rather than competing:
Foto Xplorr owns image and video — viewing, organising, and as much *editing* as can be thrown
at it, across every format achievable. Fylz owns everything else: it must be able to **open
all other kinds of files**, and to **edit plain text** the way Notepad does on Windows and
TextEdit does on Mac. Both apps route their AI needs — on-device or cloud — through **ASOM
(A System of Models)**, the constellation's model/inferencing router: download models and
configure inference once, every app connects.

## 1. Fylz as the universal opener

Where it stands: previews cover text/source/Markdown and agent artifacts, images (SVG, GIF,
animated WebP), PDF, media metadata, archives (browsable, member extraction), spreadsheets,
presentations, wireframes/glTF, fonts and unknown-file inspection; `openExternal` hands
anything else to the system chooser (now also on Alt+click). The bounded inspector is the
honest floor: every file opens as *something*, even if that something is a hex/metadata view.

Gaps to close, in order: (1) an "Open as…" chooser (text / archive / image / hex) for
misnamed or extensionless files, so the registry's guess is never the last word; (2) audio
and video playback parity through the shared route Foto Xplorr uses, without becoming a
gallery — one file playing, not a library; (3) EML/vCard/ICS structured previews, the last
common desktop formats with no route. Editing beyond plain text stays out of scope by
doctrine: a spreadsheet or image is *opened* here and *edited* in the app that owns it.

## 2. Fylz as the plain-text editor

Where it stands: bounded UTF-8 editing with pre-write history capture exists. The
Notepad/TextEdit bar means: find and replace within the open file; go-to-line; word wrap
toggle; line-ending display and preservation (a file opened LF must save LF); encoding
display with explicit re-read-as; unsaved-change guard; and the size ceiling *reported* with
a read-only fallback, never silent truncation (the spreadsheet preview already sets this
precedent). Syntax highlighting for the formats the previewer already recognises is a finish,
not a requirement. Each of these is a bounded work item on the existing editor; none needs a
new architecture.

## 3. ASOM routing (both apps)

Fylz today has three AI-adjacent pieces, all local-first and policy-gated: `LocalModelManager`
(checksum-verified packs), `AiClient` (BYOK remote with `AiTransmissionPolicy` preview), and
`ApiKeyVault`. ASOM becomes the **preferred first route**: when installed, model needs go to
ASOM; the app's own local packs and BYOK remain fallbacks in that order, and the transmission
preview keeps applying to anything that leaves the device regardless of who routed it.

The proposed integration contract (`ai/AsomLink.kt` carries the constants):

- **Discovery:** ASOM is detected by package (`dev.aarso.asom`, PROPOSED — the creator names
  apps, so this constant is explicitly subject to his correction) with a declared service
  action (`dev.aarso.asom.action.ROUTE`, PROPOSED).
- **Transport:** a bound service with a small AIDL surface (request: task kind, input,
  constraints; response: output, model used, on-device flag) — chosen over a ContentProvider
  because inference is a call, not a table, and over intents because payloads exceed extras.
- **Sovereignty invariants, non-negotiable whoever routes:** no request leaves the device
  without the per-request transmission preview; ASOM's on-device/cloud decision is surfaced,
  not hidden; absence of ASOM degrades silently to the app's own routes; no telemetry in
  either direction.
- **Not built here:** the AIDL file and the binding code wait on ASOM exposing the service —
  building both sides of a contract from one side bakes in guesses. What Fylz lands now is
  the seam: detection, the preference ("Use ASOM when available", default on once ASOM
  ships), and this contract for Madhav to veto or amend. Foto Xplorr adopts the identical
  seam.

## 4. What Foto Xplorr owes this contract (for its own work sessions)

Recorded here so the Fylz side of the boundary is explicit: media files opened in Fylz get
preview and playback but never editing tools; "Edit" on an image or video in Fylz is a
hand-off to Foto Xplorr when installed (same detection pattern as ASOM), else the system
chooser. Foto Xplorr in turn hands non-media files back rather than growing openers for them.
