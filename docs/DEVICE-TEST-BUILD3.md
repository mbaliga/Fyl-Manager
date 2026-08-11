# Device test script — Build 3 line (spatial shell + rooms)

**For:** Madhav, on real hardware. **Prepared:** 9 Aug 2026 (WP-0.8, extended for the
details/actions rooms of PR #14).
**Covers:** the four rooms, the sort-keyed edge scrubber, shake-to-refresh, the theme fix, the
restyled crash screen, and all-files-access detection. All of this is unit-tested; none of it has
ever been exercised on hardware. **Findings from this checklist are Phase 2 input** — especially
anything gesture-related, which feeds WP-2.1 (gesture-safe seams, predictive back).

How to use: work top to bottom in one sitting (~25 min). For each numbered item mark **OK**,
**ISSUE** (describe), or **N/A**, in the margin or a copy of this file. Film anything that feels
wrong — motion bugs rarely survive translation into prose.

Reference for what "right" feels like: `docs/fonebrew-navigation.md`. The one-line version:
drags track the finger 1:1, release decides, settles are ~320 ms eased with **no spring**, and
nothing fades in from nothing.

---

## 0. Setup

- [ ] Install the debug (or stripped release) APK on the primary test device.
- [ ] Have one folder with a mixed bag of files (images, PDFs, a ZIP, text), one folder with
      50+ files, and one folder of date-named logs (`2024-01-03.log` style — for the scrubber's
      `#` bucket).
- [ ] Note device, Android version, and whether gesture navigation or 3-button is active:
      ____________________________________________

## 1. Rooms — reveal gesture, each edge

For each edge, drag in from it slowly, then again with a fast flick.

- [ ] 1.1 **Left (locations).** Slow drag: the browser lifts, shrinks slightly, gains rounded
      corners and a ring, and slides right, revealing the word-wheel 1:1 under the finger.
      Releasing before halfway returns home; after halfway (or any fast flick) settles open.
- [ ] 1.2 **Right (tools & settings).** Same physics, mirrored.
- [ ] 1.3 **Top (details).** Pull down from the top edge. The details surface is revealed
      *above*; the browser parks downward. Confirm the room arrives slightly under-scale and
      reaches full size exactly as the drag completes (it should read as settling into place,
      not a panel sliding in).
- [ ] 1.4 **Bottom (actions).** Drag up from the bottom edge. Same check.
- [ ] 1.5 **No spring.** Watch several settles closely: any overshoot/bounce is an ISSUE.
- [ ] 1.6 **Mid-flight reversal.** Start opening a room, reverse the drag without lifting.
      The motion must invert smoothly — no snap, no restart.
- [ ] 1.7 **Gesture-nav conflict (important Phase 2 input).** With system gesture navigation
      on, try 1.1 and 1.2 near the vertical centre of the screen edges. Note how often the
      system back gesture wins over the room drag: ____________________
- [ ] 1.8 **Corners stay honest.** Start a drag in the top-left corner moving *down* — the
      list must scroll, not stall or open a room.

## 2. Rooms — the way back

- [ ] 2.1 With a room open, **tap** the parked browser card: returns home.
- [ ] 2.2 With a room open, **drag** the parked card back: 1:1 tracking, release decides.
- [ ] 2.3 Drag the card *past* home toward the opposite edge in one motion (left room →
      straight into right room): continuous, no wall at centre. Vertically too. A room the app
      doesn't have on that edge must never open as an empty void.
- [ ] 2.4 **Back button/gesture** with a room open: closes the room. Pressed again at home in
      a nested folder: goes up the folder stack, not out of the app.
- [ ] 2.5 While a room is open, the parked card swallows touches — nothing under it may fire.

## 3. Top room — details

- [ ] 3.1 No location open (storage home): pull down. The room explains itself rather than
      showing an empty tree.
- [ ] 3.2 Open a folder a few levels deep, pull down: the "Where it lives" tree shows the
      full path expanded with indent guides, the current folder marked with the accent bar,
      subfolders of the current folder beneath it, collapsed.
- [ ] 3.3 Tap an ancestor row: navigates back up to it and closes the room.
- [ ] 3.4 Tap a subfolder row: opens it and closes the room.
- [ ] 3.5 Focus a file (tap it in the list), pull down: the file is the subject — name as
      title, marked row in the tree, Size/Modified/Type/Location facts. If any file shows
      "Not reported", confirm the file genuinely has no size/date from its provider (SAF
      virtual files, some cloud providers), not a lie.
- [ ] 3.6 Select several entries, pull down: a summary (count, total size, kinds), not a
      single file's pane.
- [ ] 3.7 In a folder with more than ~8 subfolders: the tree shows a window of them plus
      "N more in this folder" — and the focused entry is always visible in the window.
- [ ] 3.8 Tap the **top bar title**: opens the same details room (the tap path for what the
      drag does).

## 4. Bottom room — actions

- [ ] 4.1 No selection, folder open: "This folder" section (New folder, New text file, Scan
      to PDF, Find duplicates when >1 file, AI proposal when a file is focused) and
      "Storage & recovery" beneath. No selection section.
- [ ] 4.2 Select one file: "Selection · 1 item" appears with Rename but **no Batch rename**.
- [ ] 4.3 Select two: Batch rename appears, Rename disappears.
- [ ] 4.4 Select one ZIP: Extract appears. Select a second file: it disappears.
- [ ] 4.5 Select only PDFs: PDF tools appears. Mix in an image: it disappears.
- [ ] 4.6 Include a folder in the selection: **Share is absent** (folders can't ride
      ACTION_SEND). Deselect the folder: Share returns.
- [ ] 4.7 Run one Copy to… and one Move to… end-to-end from the room: destination picker
      appears, operation completes, toast confirms, room stays closed.
- [ ] 4.8 Selection summary bar (bottom of the list): count reads correctly, **Clear** empties
      the selection, **Actions** opens the bottom room by tap.
- [ ] 4.9 Recovery section: Operation history opens; File history / Backups / Import /
      Archive tools all open their overlays *in the app's theme* (see §7).
- [ ] 4.10 The whole room scrolls as one surface — no nested-scroll fight, no crash (the
      recovery section must not own its own scroll).

## 5. Edge scrubber (sort-keyed)

In the 50+ file folder:

- [ ] 5.1 Sort by **name**: scrubber shows letter stops; scrub — the list jumps under the
      finger (tracks, not chases); the bubble shows the letter.
- [ ] 5.2 Sort by **date**: stops become month labels ("Mar 25"); undated files bucket as "—".
- [ ] 5.3 Sort by **size**: stops become bands (dir/0/B/KB/MB/GB).
- [ ] 5.4 Sort by **type**: stops become extensions; extensionless files bucket as "·".
- [ ] 5.5 The date-named-log folder, sorted by name: **one** `#` stop, not a thousand.
- [ ] 5.6 Grid view: scrubbing still works and lands on the right rows.
- [ ] 5.7 Storage home (no folder): no scrubber. Make a selection: scrubber yields to the
      selection bar and disappears.

## 6. Shake to refresh

- [ ] 6.1 In a folder, add a file from another app; shake the device deliberately: listing
      refreshes.
- [ ] 6.2 Normal handling — walking, setting the phone down — must **not** trigger it.
- [ ] 6.3 The toolbar Refresh button does the same thing for anyone who won't shake.

## 7. One theme everywhere (the fix that shipped three bugs late)

With the device in **dark** theme:

- [ ] 7.1 Main browser: dark. Locations / tools / details / actions rooms: dark.
- [ ] 7.2 Tools screen (right room → Tools) and Index manager: dark, not the old light island.
- [ ] 7.3 Recovery overlays (file history, backups, archive tools): dark.
- [ ] 7.4 Theme toggle in the right room: Light / Dark / Follow the system all apply
      immediately, everywhere, no restart.

## 8. Crash screen (restyled)

Only if a debug crash trigger exists in this build; otherwise skip.

- [ ] 8.1 The crash-recovery screen matches the app (two-pane recovery UI), offers
      report-copy and reset paths, and "Reset app data" is loop-gated, not one tap away.

## 9. All-files access (full flavor)

- [ ] 9.1 Fresh install → home surface explains storage access state honestly.
- [ ] 9.2 Grant all-files access in Settings and **return to the app**: storage roots appear
      on their own, without relaunch.
- [ ] 9.3 Deny it and use the SAF picker instead: the app remains fully usable (this is the
      MANAGE_EXTERNAL_STORAGE-never-required invariant, live).

## 10. Cluster drag and corner bulges

- [ ] 10.1 Select 3 files, press-hold one of them: the selected rows gather into a card
      cluster under the finger (cards fly from their rows, fan slightly, count badge on top).
      Corners grow bulges: actions top-left, trash bottom-right.
- [ ] 10.2 Drag toward the **trash**: the can tilts, lifts a little, lid swings open — all
      continuously with distance, reversing smoothly when you retreat.
- [ ] 10.3 Release on the trash: the cluster pours in with a genie squeeze; files land in the
      Recycle Bin; the bottom-right resting bulge appears with a count.
- [ ] 10.4 Drag to the **clipboard** slot: it swells as you near; release snaps the cards to
      the corner. The top-left resting bulge appears with a count.
- [ ] 10.5 Release over nothing: the cluster springs back to the rows; nothing happens.
- [ ] 10.6 Release on **New folder**: name dialog appears; confirming creates the folder and
      moves the files in. On **Compress**: the ZIP creator opens for the selection.
- [ ] 10.7 Tap the top-left resting bulge: the tray expands. Flick the strip both directions —
      it loops endlessly with no wall and no jump (try with 1 item and with 5).
- [ ] 10.8 Pull a card straight down past the threshold: it leaves the tray. TalkBack: each
      card exposes a "Remove …" accessibility action.
- [ ] 10.9 "Paste here" in a nested subfolder copies into THAT folder, not the root. "Move
      here" moves and empties the move tray; the clipboard keeps its contents after pasting.
- [ ] 10.10 Tap the trash resting bulge: this session's items list with **Put back** and
      **Shred**. Put back restores to the original folder.
- [ ] 10.11 Shred: the confirm shows the shredder animation and the honest not-a-forensic-wipe
      copy; confirming permanently deletes; the strips animate while it runs.
- [ ] 10.12 With a room open or the storage home showing: no bulges anywhere (they live in
      the browser only). Back closes shred confirm → sheet → room, in that order.
- [ ] 10.13 Long-press on an UNSELECTED row still just selects it; drag-scroll of the list
      still works normally with a selection active.

## 11. Free-form notes

Anything that felt wrong, slow, or surprising — one line each, no filter:

```
…
```
