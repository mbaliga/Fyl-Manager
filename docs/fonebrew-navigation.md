# The fonebrew navigation pattern — constellation reference

> Owner direction (2026-08-05): *"I expected the navigation pattern of fonebrew to be followed
> everywhere."* This file is the written form of that pattern, distilled from the owner's two
> reference videos, the fonebrew (Android-IDE-core "Workbench") rooms implementation, and the
> correction round on the first Foto Xplorr / Fylz test builds. It exists so no future build
> session re-derives the pattern from memory — or worse, ships hamburgers and bottom nav bars
> again.

## What fonebrew navigation IS

**Rooms, not screens.** The app is a small set of rooms. Moving between rooms is the primary
navigation act and it happens **on the content**, not through chrome.

**The rail is a word-wheel** (reference video 2 — the scrolling word list):

- Room names are large text in a vertical run. The **focused** room is the brightest and
  heaviest; neighbours dim and lighten **progressively with distance** — the fade IS the
  scroll position indicator, so no scrollbar, no highlight box, no selected-item background.
- A small **morphing bullet** rides beside the focused row (dot → dash → dot-trail as focus
  moves). It travels *between* rows during the transition rather than teleporting: motion
  carries the state change (Hyle: state is shown, not said).
- Scrolling the rail feels like turning a wheel: rows approach the focus line, gain weight,
  pass it, lose weight. Settle snaps the nearest row to focus.

**The top room** (reference video 1 — the fluid top-bar reveal):

- Pulling down from the top of any room reveals a space ABOVE it — the top room. The reveal
  is a **fluid melt** (the bar/notch area stretches and flows open around the pull, like the
  video's island morphing around focus), never a rectangular slide or a fade.
- **The top room owns pull-down starting in a fixed band at the very top of a room — in this
  repo, `SpatialShell`'s `EDGE_DP` (56dp), claimed on `PointerEventPass.Initial` before any
  content beneath it ever sees the pointer.** Nothing may out-race that claim, and nothing here
  changes that: a pull starting inside those 56dp still opens the top room, always, in every app.
- **Below that band is a different question, and Fylz (Build 10) answers it differently than the
  first cut of this rule did.** The original wording banned pull-to-refresh *and everything else
  pull-down* everywhere below the band too, on the theory that the gesture belonged to the top
  room full stop. Fylz now reveals search that way: pulling down on a listing that is already at
  its own absolute top, starting below the shell's 56dp claim, reveals a search field above the
  listing (`ui/search/PullDownSearch.kt`) rather than doing nothing. That is a narrowing of the
  old rule, not its repeal — pull-to-refresh and pull-to-backup stay banned, refresh stays a
  shake (below), and the top room's own 56dp claim is never contested, only the space past it.
  An app adopting this pattern that has nothing to put below the band leaves it unclaimed, same
  as an app with nothing for the top room itself leaves that slot null.
- The top room is NOT required in any app (owner, 2026-08-05). What is required is keeping its
  gesture unclaimed and its copy off the screen — no "PULL TO …" static text. An app that has
  something worth putting up there may build it; Fylz did (details), Foto Xplorr's is
  notifications and alerts. An app with nothing to put there leaves the slot null, and the
  shell then refuses top drags outright rather than opening a void.

**Refresh is a shake**, not a pull (`ShakeToRefresh` in this repo, `hyle/ShakeToRefresh.kt`).
A deliberate physical gesture that needs no affordance copy and competes with no scroll — this
holds regardless of the top-room carve-out above, since revealing search is not refreshing
anything and claims none of the space refresh was ever moved off of.

## What fonebrew navigation is NOT

These are the things the first test builds got wrong. Do not reintroduce them:

- **No hamburger menu** as the primary way into navigation. The rail is reachable by edge
  swipe and lives *over the content*; a small header affordance may open it but must not be
  a `Menu` icon opening a drawer-style list.
- **No bottom `NavigationBar` / tab bar picking between app sections.** (Fylz shipped
  "Files / Recovery" tabs — wrong; Recovery is a room now.) This is about a *second*,
  app-level nav surface competing with the rail — it is not a ban on tabs as a word. Build 9
  gave the browser folder tabs (multiple open folders, opened and closed by what the session
  is doing) living in a strip inside the command pill; Build 10 restyled and relocated that
  strip to its own bottom band (`ui/chrome/TabBand.kt`, overlapping folder-shaped tabs on a
  black plinth, per the owner's exact Figma spec) without changing what it IS — it still
  floats over the content of the one room that has folders open, and it is still reachable the
  same way through the rail's own word-wheel when no folder is open. That is content-scoped
  navigation for a place you're already in, the same category as the up-arrow beside it — not
  a rival to the rail for "which section of the app am I in."
- **No full-screen Material settings dialogs.** Settings slide in as a panel over the room
  (Foto Xplorr's `SlideInPanel` from the right is the shape); a centered `AlertDialog`-style
  "Gallery settings" card is wrong.
- **No static instructional copy in gesture spaces** ("PULL TO CREATE BACKUP"). If a gesture
  needs a permanent caption, the gesture is wrong.

## Where each app stands (2026-08-05; Fylz row 2026-08-09)

| App | Has | Needs |
|---|---|---|
| fonebrew (IDE-core) | rooms implementation (`ui/rooms/`) | is the reference |
| Foto Xplorr | nine destinations + `SlideInPanel` rail exists, but presented behind a hamburger; settings are a Material dialog | word-wheel rail presentation + motion; settings → slide-in panel; hamburger retired |
| Fylz | full pattern: word-wheel rail, edge scrubber, shake-to-refresh, one theme, and all four rooms (locations, tools, details, actions) | — |
| csapp / assay | standard Material consoles | pattern adoption once the two testable apps validate it |

## Motion notes for the implementer

- Rail focus transitions: single spring (medium stiffness, slight overshoot acceptable on
  the bullet, none on text weight). Text weight/alpha interpolate with the row's distance to
  the focus line *continuously during drag* — not at settle.
- The reveal melt: drive a corner/edge distortion from pull distance; the surface being
  revealed scales from ~0.97 and un-blurs. Nothing fades in from nothing — material flows.
- Every transition must remain interruptible mid-flight (drag reversal inverts it).
