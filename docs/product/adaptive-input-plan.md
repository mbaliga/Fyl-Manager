# Adaptive layout and physical-input plan

**Prepared 22 Aug 2026.** Two owner-side requirements: landscape and foldable support as a
first-class concern, and mouse/keyboard/external-display support in anticipation of ChromeOS
being folded into Android (desktop windowing becoming ordinary Android behavior). This is the
assessment and the plan; only the keyboard wiring below is implemented alongside it.

## Where the code stands

- **One breakpoint.** The shell's entire adaptivity is `wide = maxWidth >= 900.dp`
  (`FylzV1App`). Two consequences: a landscape phone (~780–850dp wide) gets the portrait
  phone layout rotated, with the top bar and bottom actions eating scarce vertical space; and
  an unfolded book-posture foldable (~670–840dp) is likewise treated as a tall phone.
- **No posture awareness.** Jetpack WindowManager (`FoldingFeature`) appears nowhere: no
  hinge avoidance, no tabletop/book posture, so a preview pane can straddle the fold.
- **Keyboard: policy without a listener.** `workspace/KeyboardShortcutPolicy` defines the
  command vocabulary (select-all, copy, move, rename, delete, search, escape…), resolves
  gestures, and prints platform-aware labels — and has **zero call sites**. No
  `Modifier.onKeyEvent` in the shell, no focus order, no shortcut reachable from a real
  keyboard. (Same shape as `CapabilityPolicy` before Phase 2 consultation: built, tested,
  unwired.)
- **Pointer: partial.** Hover states exist in several components (`FylzAppShell`, QuickLook,
  overview cards, preview pagers), but there is no right-click surface (context menu), no
  scroll-wheel tuning, and the edge-drag room reveals have no pointer-friendly equivalent
  beyond their tap fallbacks where those exist.
- **External displays: nothing.** No multi-display awareness, no desktop-windowing manifest
  affordances beyond `resizeableActivity` defaults.

## The plan

### WP-A1 — three-tier width classes, honest landscape (foundation)

Replace the 900dp cliff with the canonical `WindowSizeClass` tiers (compact <600dp, medium
600–840dp, expanded ≥840dp), and make LANDSCAPE-compact a real layout: rooms stay edge-parked,
but chrome collapses (top bar merges with the tab band, actions bar goes rail), and the
listing gets the height back. Medium gets the docked preview pane the wide layout has, at a
narrower default. This is the prerequisite for every other item; it touches the shell's core
and must be felt on hardware — implement first, gate on the device checklist.

### WP-A2 — foldable postures

Add `androidx.window`'s `WindowInfoTracker`: in book posture, bias the two-pane split to the
hinge; in tabletop, park the preview/details on the top half and controls below; never let a
dialog or the notched preview card straddle an occluding hinge. Verify with the foldable rows
of `DEVICE_ACCEPTANCE.md` (already written there as acceptance items — currently untestable
by code alone).

### WP-A3 — keyboard, wired (implemented with this document)

Wire `KeyboardShortcutPolicy` into the shell: one `onPreviewKeyEvent` at the shell root
translating hardware key events through the existing `resolve()`, dispatched to the same
handlers the touch UI calls (no second command path). Escape closes rooms/selection the way
back does; the policy's `label()` strings become the future shortcut-map sheet's content.
Focus traversal and visible focus rings across the grid remain WP-A3b with the accessibility
acceptance pass — they need TalkBack-adjacent testing that only hardware honestly provides.

### WP-A4 — pointer parity

Right-click context menu mirroring the actions room's selection section (same
`SelectionActionPolicy` output, so capability gates apply automatically); scroll-wheel page
turns in the pagers; hover affordances for the room edges (a subtle handle on pointer hover,
since edge-drag is a touch idiom); pointer-precision resize for the docked preview divider.

### WP-A5 — external displays / desktop windowing

Freeform-window sanity pass (min sizes, resize continuity — the journal and rooms must
survive aggressive resizes), multi-display behavior (open-on-secondary, move between
displays), and a Chromebook/DeX manual acceptance checklist appended to
`DEVICE_ACCEPTANCE.md`. The ROADMAP's "full keyboard shortcut map and Chromebook/desktop-mode
polish" backlog line resolves through WP-A3 + WP-A5.

## Order

WP-A3 now (bounded, no layout risk). WP-A1 next PR (core layout, device-gated). WP-A2 after
A1 (postures depend on the tiers). WP-A4 alongside A1/A2 opportunistically. WP-A5 last — it
is mostly acceptance work on hardware Fylz cannot simulate.
