# Apple HCI study — the principles behind Build 6

A research pass over Apple's Human Interface Guidelines and the behaviour of Finder, Files,
Quick Look and Spotlight, distilled to what governs Fylz decisions. Each section ends with the
rule Fylz adopts. This document is normative for Build 6 and advisory afterwards.

## 1. The principles

Apple's current HIG (the 2025 Liquid Glass revision) organises design around three words:

- **Hierarchy** — prioritise the most important content and actions; the interface guides focus
  rather than competing for it. Content first, controls in service of it.
- **Harmony** — an app should feel like a natural extension of the platform: system materials,
  established gestures, familiar component behaviour.
- **Consistency** — standard actions behave the way users already expect; never reinvent a
  back button or a search field.

The older canonical trio still underpins those: **clarity** (text legible at every size, icons
precise, functionality motivating design), **deference** (the UI helps people understand and
interact with content, never competes with it), **depth** (visual layers and realistic motion
convey hierarchy and position). Alongside them, the timeless mechanics: **direct manipulation**
(people manipulate content, not proxies for it), **feedback** (every action acknowledged),
**user control** (people, not the app, are in charge; actions reversible), **progressive
disclosure** (show the common, reveal the advanced), and **aesthetic integrity** (appearance
matches purpose).

The single most load-bearing sentence for a file manager: **deference — the content is the
interface**. A row of files is the user's stuff; every pixel of chrome laid over it must justify
itself against the stuff it covers.

## 2. Selection — why persistent checkboxes are wrong

Neither Finder nor Files shows a checkbox at rest. Apple's model:

- **At rest, a row is content.** Selection affordances do not exist until the user asks for
  selection. Finder selects by click/drag; Files (iOS) enters an explicit Select mode, or the
  iOS 13+ **two-finger pan** begins multi-select directly on a table/collection with no mode
  button at all.
- **In selection mode**, the marks appear *on* the items (Photos-style corner badge), the app
  chrome swaps to selection chrome (count, actions), and leaving the mode removes every mark.
- Selection state is shown primarily by **tint on the item itself** — the mark is a
  confirmation, not the mechanism.

**Fylz rule:** no checkbox exists outside selection mode. Long-press (already the selection
gesture) enters selection mode; while it is active, items show a corner check badge and tint,
tap toggles membership, and the mode ends when the count reaches zero or Clear is tapped.
The picker keeps checkboxes only in FILES mode, where selection *is* the screen's purpose —
that is the Files-app "Select" screen, not a browse surface.

## 3. Quick Look — why the preview must fit the content

Quick Look's contract since 2007: press space, the file appears **at the content's own aspect
ratio**, at full or near-full size, in a window that is nothing but the content plus a hairline
title bar. No letterboxing into a fixed frame; an A4 PDF opens tall, a 16:9 video opens wide.
The window is the content's shape.

**Fylz rule:** the preview card takes its aspect ratio from the content — image pixel size,
video frame size, PDF page box — clamped to sane viewport bounds. The user's remembered size
becomes a remembered *scale* (how large), not a remembered box (what shape). Grey gutters are a
defect by definition: if the card and the content disagree about shape, the card is wrong.
Free-form aspect applies only to content with no intrinsic shape (text, inspector).

## 4. The desktop-class view

Finder's list view is the desktop file idiom: dense rows, **columns** (Name, Size, Kind, Date),
sortable by tapping a column header, per-folder view memory. Gallery view pairs a large preview
with a filmstrip. macOS's "Show all filename extensions" is a Finder-level toggle; without it,
extensions show per-file.

**Fylz rule:** a third view mode, **Details** — a Finder-style dense multi-column list (Name |
Size | Modified, kind expressed by the icon), column headers tappable to sort, driven by the
existing SortSpec so the scrubber's stops re-key with it. View mode persists. "Show file
extensions" becomes a setting (default on — this is a file manager); when off, known extensions
are stripped from *display* names everywhere a name is shown, never from the underlying name.

## 5. Motion and animated previews

Finder plays video previews in place (gallery view, hover scrub); iOS uses motion to preview
content, always **content motion, not chrome motion** — the HIG's motion guidance is that
animation communicates; it never decorates. Autoplay is quiet: muted, local, interruptible.

**Fylz rule:** an "Auto-animate previews" setting (default on). In the listing, video
thumbnails cycle a few frames (muted, low rate, only while visible). In the preview card, video
and GIF content starts playing automatically (muted). Off = today's static behaviour. Motion
never plays on chrome; only on content.

## 6. Search — the Spotlight bar

Spotlight accepts **natural language** ("images from last week", "documents I created in May",
"slides from 2016 containing WidgeTech") alongside a structured grammar (`kind:pdf`,
`created:>10/03/2022`, name terms). The lessons:

- One field accepts everything; the *parser* decides what was meant. No mode switch between
  "normal" and "smart" search.
- Recognised fragments become **visible interpretations** (Spotlight shows what it understood;
  modern search UIs render the parsed filters as removable tokens/chips) — the user can see and
  correct the machine's reading.
- Ranking is unapologetic: best match first, not directory order. Recency, name-match quality
  and kind all weigh in.

**Fylz rule:** one search field; a parser in the shared search library turns free text into a
structured spec — terms + kind + date range + size range — rendering what it understood as
removable chips above the results; ranked results, not filtered listings, whenever the query
carries intent beyond a bare substring.

## 7. Applied: the Build-6 checklist

| HIG principle | Fylz change |
|---|---|
| Deference / content-first | Checkboxes exist only in selection mode; preview card fits content shape |
| Direct manipulation | Selection marks live on items, not in a gutter column |
| Consistency | Search is one field that understands more, not a second "smart" mode |
| Hierarchy | Details view puts name first, metadata in quiet columns |
| User control | Anchor keeps a preview alive across navigation; Dock parks it without loss |
| Progressive disclosure | Extensions, animation, icon style are Settings, defaulted sensibly |
| Feedback / motion | Animated previews are content motion, muted, interruptible |
