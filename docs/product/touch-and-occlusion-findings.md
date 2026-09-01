# Thumb occlusion and touch-target findings

**Status: findings and proposals, not fixes.** Build 13c's touch-target pass measured every
clickable site under `app/src` and fixed what it owned; its commit message stated that thumb
occlusion was *not* fixed, that every real occlusion defect sits in the drag/drop drop-commit
path, and that the fixes change drop-commit semantics — a decision for the owner, not a
mechanical patch. The analysis behind that statement was never committed; this document
reconstructs it from the code so the findings survive the session that made them.

## Where occlusion actually bites

The cluster drag is the one surface where the finger, the dragged content and the commit
decision all occupy the same pixels.

### F1 — the dragged stack is centred under the finger

`ui/cluster/ClusterDrag.kt` draws each `ClusterCard` at `position − 36dp` on both axes: a 72dp
card centred exactly on the touch point, with the rest of the fan stacked behind it. Between
the fingertip and the lead card, the landing zone is fully hidden at the only moment that
matters — release. The stack reads beautifully in motion (the underdamped springs in
`ClusterPhysics.kt` are doing real work), but at commit time the user is aiming blind.

**Proposal.** Anchor the spring target ~28dp above-left of the touch point (mirrored for
left-thumb reach is not detectable; above-centre is the safe default), the way platform drag
shadows sit above the finger. This is a pure presentation offset — `DropTargetPolicy` keeps
deciding from the *finger* position, so drop semantics do not change, only visibility. Risk:
near the top edge the offset stack collides with the toolbar; clamp the presentation offset to
the parent bounds.

### F2 — the corner bulges are hidden by the hand that reaches them

The drop targets are corner blobs (`ui/cluster/Bulges.kt`, resting size 58dp, hit radius 34dp
around each slot in `ClusterDrag.kt`). A right thumb dragging to the top-left bulge crosses the
whole screen; forearm and thumb occlude the corner, the bulge's swell/brighten reaction
(`Bulges.kt`, "swells and brightens as the finger nears") and the hot wash on trash — every
"you are about to commit here" signal — precisely when they are needed.

Two mitigations already exist and are good: the caption naming the nearest slot is "drawn
clear of the arc" (`Bulges.kt`), and slot reactions are proximity-ranked so only one slot
claims the drag. What is missing is a commit signal that survives a hidden corner.

**Proposal, in preference order.**

1. **Armed-state echo at the stack.** When `DropTargetPolicy` ranks a slot as the drop, badge
   the lead card itself (icon + slot name on the card face). The lead card is the one thing
   the user is certainly looking at; with F1's offset it is also visible. No semantic change.
2. **Release-to-confirm for destructive slots only.** Trash commits on release today once
   `hit` is true. An occluded hot wash means a hidden red warning. Keeping non-destructive
   slots on release-commit but requiring a 150ms dwell inside the trash radius before it arms
   changes drop-commit semantics — this is exactly the class of change Build 13c deferred, and
   it should be device-felt before it ships.
3. **Do not enlarge the hit radius as an occlusion fix.** 34dp radius against a 58dp visual is
   already honest; growing the radius makes blind drops *commit more often*, which is the
   wrong direction when the user cannot see the target.

### F3 — the room-edge drags and the scrubber are self-occluding by design, and acceptably so

The edge scrubber and the four room reveals track 1:1 under the finger
(`docs/fonebrew-navigation.md` is the contract). The finger hides the scrubber's current
bucket and the room's leading edge, but both surfaces continuously display their state ahead
of the finger (the revealed room fills the screen; the scrubber's bucket label rides above the
touch point). Measured against the reference behavior, no change is proposed. If device
acceptance (`DEVICE-TEST-BUILD3.md` §1) reports mis-drops here, revisit.

## Touch-target floor: the three reported sites

Build 13c measured every interactive site and grew what it owned. Three sites in `ui/chrome`
remain 1–4dp under the 48dp floor and were reported rather than restyled, because that file
set is the owner's literal Figma geometry (`ChromeTokens.kt` and siblings state this):

| Site | Today | Floor gap |
|---|---|---|
| `SelectionRow.kt` — `SelectionRowHeight = 44.dp`, the whole selection bar row incl. its close glyph's cell | 44dp | −4dp |
| `TabBand.kt` — `TabStripHeight = 47.dp`, every folder tab's touch height | 47dp | −1dp |
| `TabBand.kt` — the tab-close / trash cell, `width(44.dp)` | 44dp | −4dp |

**Proposal.** Keep the drawn geometry exactly as exported; grow only the *touch* cells with
`Modifier.pointerInput` hit expansion (or `minimumInteractiveComponentSize` where the visual
already floats in spare space), the same visual-stays-small pattern the Build 13c pass applied
elsewhere. This needs no owner design decision — it does not move a pixel — but it touches the
owner's files, so it is listed here first rather than silently applied.

## What acting on this looks like

F1 and F2.1 are safe now (presentation only). F2.2 changes commit semantics: implement behind
the device checklist, feel it on hardware, then decide. The chrome hit-cell growth is a small
PR on its own. All of it should land *after* the current branch merges, so the device pass on
Build 13c tests what was actually built.
