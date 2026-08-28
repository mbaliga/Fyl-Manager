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

For **§10 only**, use the focused failure log in that section: do not write up successful
gestures, and do not record a failure unless it meets that section's fix-before-Phase-2 filter.

Reference for what "right" feels like: \`docs/fonebrew-navigation.md\`. The one-line version:
drags track the finger 1:1, release decides, settles are ~320 ms eased with **no spring**, and
nothing fades in from nothing.

---

## 0. Setup

- [ ] Install the debug (or stripped release) APK on the primary test device.
- [ ] Have one folder with a mixed bag of files (images, PDFs, a ZIP, text), one folder with
      50+ files, and one folder of date-named logs (\`2024-01-03.log\` style — for the scrubber's
      \`#\` bucket).
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
- [ ] 5.5 The date-named-log folder, sorted by name: **one** \`#\` stop, not a thousand.
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

## 10. Focused interaction-tuning pass — cluster drag, corner bulges, clipboard, shred

**Purpose.** This is a short real-device tuning pass for the interactions added in PR #14.
It targets touch pickup, finger tracking, continuous corner response, hit-zone clarity, and
terminal flight curves. It is not a second provider, journal, accessibility, or full acceptance
matrix. Use disposable local files and stop when the failure log is complete.

**Do not record OKs.** Leave a blank when a gesture works. Record an entry only when:

- the failure is repeatable on **2 of 3 attempts**; or
- it is a safety/data-loss/blocking failure that is worth fixing after one occurrence.

A failure belongs in the **before-Phase-2** log only when it does at least one of these:

- chooses the wrong target, fires an action without a clearly intended release, or makes a
  constructive target plausibly land in the destructive corner;
- loses, duplicates, or miscounts selected/staged files, or cannot return them to their rows;
- makes the cluster or a bulge jump, stutter, clip, disappear, or stop responding in a way that
  changes where the user believes the release will land;
- leaves the visual landing and the actual operation out of sync, leaves stale cards behind, or
  crashes/locks the interaction;
- makes a common one-handed target materially hard to hit, or lets the gesture steal a room/list
  gesture repeatedly;
- makes **Shred** bypass explicit confirmation, show the wrong item/count, fail to cancel/back out,
  or present a misleading completion state.

Do **not** record:

- successful attempts;
- a one-off miss caused by releasing outside a target;
- a purely subjective preference about colour, shape, speed, or whimsy when the target remains clear
  and the action remains reliable;
- provider latency/error that is honestly surfaced and does not alter the gesture result;
- forensic-wipe expectations. Shred is permanent deletion from Fylz and its Recycle Bin, not a
  forensic erase.

**Test setup.**

- [ ] Use the primary phone, one orientation, the normal display scale, and the current PR #14
      debug APK. Record device/Android/nav mode once at the top of the failure log.
- [ ] Create three disposable local files with different names/sizes. Select all three. Keep them
      recoverable until the pass is complete; use a second disposable set for the final Shred test.
- [ ] Film only a failure, from the side or screen-recording view that shows the finger and the
      corner target. Do not spend time recording successful runs.

### 10.1 Pickup, cluster weight, and reversal

Press-hold a selected row, then repeat once from a row near the top and once near the bottom.

- [ ] Failure only: the cluster does not gather under the finger, contains the wrong count/files,
      starts visibly away from the held row, or the \`+N\` badge is wrong.
- [ ] Failure only: cards never become a readable cluster, separate so far that the drop target
      becomes ambiguous, clip against the screen, teleport, oscillate, or trail the finger so far
      that release intent is no longer clear.
- [ ] Failure only: a slow drag, a fast turn, or a mid-drag reversal causes a discontinuity,
      restart, stuck state, or wrong final target.

### 10.2 Corner geometry and proximity response

With the cluster active, approach the top-left action bulge, retreat before release, then do the
same at the bottom-right trash bulge. Do one slow approach and one faster approach.

- [ ] Failure only: a bulge appears outside the browser, covers the list/room controls, is clipped,
      or its shape makes the corner read as a floating panel rather than an edge swelling.
- [ ] Failure only: the icon/bulge reacts late, early, in steps, or remains hot after retreating;
      reversal is not smooth.
- [ ] Failure only: the five action slots are not distinguishable enough to select the intended
      one, or the action family and trash family can plausibly be confused.
- [ ] Failure only: the trash can's tilt/lift/lid motion, or the clipboard's swell, is missing,
      jumps, or signals a drop before the finger is close enough to make that release reliable.

### 10.3 Release, hit zones, and terminal curves

Run each path once slowly and once with a normal deliberate drag. Treat the center of a target as
the intended release; make one near-edge release only to check that the hit zone is honest.

- [ ] **Neutral return.** Release over empty space. Failure only if an action fires, files change,
      the cluster does not return to the correct rows, or the return curve visibly jumps/ends with
      stale cards.
- [ ] **Clipboard.** Release on the clipboard slot. Failure only if the wrong action fires, nothing
      stages, the count/items are wrong, or the snap flight jumps, ends away from the top-left
      bulge, finishes before the cards visibly arrive, or leaves duplicate/stale cards.
- [ ] **Trash/recycle.** Release on the trash slot using disposable files. Failure only if the
      wrong target fires, the can accepts an obviously outside release, the genie curve jumps or
      lands away from the can, the recycle result/count is wrong, or the destructive action is
      triggered without the intended drop.
- [ ] **Other action slots.** If Move, New folder, or Compress are visible and reachable, spot-check
      one. Record only a wrong target, no-op, crash, or terminal visual/actual mismatch — not the
      dialog's provider behavior.

### 10.4 Resting clipboard and tray interaction

After a successful clipboard drop, tap the top-left resting bulge, then try one horizontal flick,
one downward pull on a card, and the visible commit button.

- [ ] Failure only: the resting bulge is not discoverable/clickable without blocking ordinary
      browsing, its count is wrong, the tray opens in the wrong place, or Back/dismiss cannot leave it.
- [ ] Failure only: the tray cannot browse, remove a card by the pull gesture or accessibility action,
      or commit **Paste here**; the clipboard unexpectedly empties or duplicates files.
- [ ] If a Move tray is exercised, record only a failure where **Move here** targets the wrong
      nested folder, moves twice, or fails to empty the move tray after a successful move.

### 10.5 Shred safety and escape path

Use the second disposable set. Open the bottom-right resting bulge, choose **Shred** for one item,
then test **Keep**/Back once before reopening and confirming the deletion.

- [ ] Failure only: the trash sheet shows the wrong item/count, Shred is reachable without the
      confirmation, Keep/Back cannot escape, or Back unwinds in the wrong order.
- [ ] Failure only: the confirmation omits the honest not-a-forensic-wipe copy, its controls lock
      before the operation starts, the shredder animation never runs/never ends, or the result is
      visually inconsistent with the item being gone from Fylz and its Recycle Bin.
- [ ] Failure only: a second tap repeats the deletion, a cancelled run deletes, or the sheet/cluster
      remains stuck after completion.

### Failure log — §10 only

Use one row per failure, not one row per test. The evidence column is a filename or short note;
leave the table empty if the pass found nothing worth fixing.

| ID | Tag | Gesture / location | Repeat | Expected | Observed failure | Fix-before-Phase-2 reason | Evidence |
|---|---|---|---:|---|---|---|---|
|  |  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |  |
|  |  |  |  |  |  |  |  |

Suggested tags: **P0-SAFETY**, **P1-HIT**, **P1-STATE**, **P1-GESTURE**, **TUNE-GEOMETRY**,
**TUNE-PHYSICS**, **TUNE-CURVE**.

For the developer, map the tag to the likely seam:

- **TUNE-PHYSICS:** \`ui/cluster/ClusterPhysics.kt\` (spring stiffness/damping, fan, deform/bank).
- **TUNE-GEOMETRY / P1-HIT:** \`staging/DropTargetPolicy.kt\` and \`ui/cluster/Bulges.kt\`
  (arc centres, reaction radius, hit radius, corner shape and size).
- **TUNE-CURVE:** \`ui/cluster/ClusterDrag.kt\` (return, genie, clipboard/move snap and stagger).
- **P1-STATE / P0-SAFETY:** \`ui/cluster/TrayBrowser.kt\`, the staging/recycle callbacks, and
  the explicit Shred confirmation path.

**Exit rule:** §10 is complete when the table contains only failures that meet the filter above.
An empty table is a valid result and means no interaction-tuning change is justified by this pass.

## 11. Free-form notes for sections 0–9

Anything from sections 0–9 that felt wrong, slow, or surprising — one line each:

\`\`\`
…
\`\`\`
