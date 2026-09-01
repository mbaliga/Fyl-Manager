# Gesture vocabulary — configurable device gestures

**Owner direction (23 Aug 2026): richer, configurable device gestures.** The shake detector is
shared (`cell-shell/ShakeToRefresh`, peak-train detection, unit-tested); what a gesture *means*
becomes the user's preference, per app.

## Shipped now

**Configurable shake.** `Settings → Gestures`: Refresh this folder (default, the constellation
contract), Go home, or Nothing. The detector is untouched; only the dispatch varies, and OFF
mounts no sensor listener at all. `AppPreferencesStore.shakeAction`.

## Specced, device-gated

**Empty-the-dustbin.** Turn the phone face-down and shake, like emptying a bin over a waste
basket: opens the Recycle Bin's shred-all confirmation (never executes it — the gesture asks,
the confirmation decides, and deliberate mode applies there like anywhere else). Detection is
an orientation gate on the existing peak train: gravity's z-component sustained below about
−0.6g across every peak in the train, so a face-up shake can never trigger it. This belongs in
cell-shell beside `ShakeToRefresh` as `InvertedShake`, with the same pure-train testability.
Gated on hardware: the threshold and the false-positive rate against pocket/bag handling need
real-device tuning before the constant is frozen, and the current session cannot compile or
run anything (no SDK), so landing untested sensor code ahead of the tunable would ship a guess.

**Whip / flick.** A single sharp directional cast (the wrist-snap that would "throw" the
current selection somewhere). Distinct from a shake: one high-jerk peak with a direction, not
a train. Honest assessment: the accelerometer signature overlaps hard with sitting down,
catching a dropped phone, and putting it on a table; a reliable detector needs gyroscope
fusion and real-device data. Specced as research, not scheduled — if it survives testing, the
natural meaning is "send selection to the Shelf".

**Hints / walkthrough on shake.** The owner floated shake-to-show-hints. That needs a hints
surface to exist first; none does. If one is built, it becomes a fourth `ShakeAction` entry —
the enum and settings row are already the extension point, one line each.

## The rule all of these follow

A device gesture may open, reveal, or ask. It never destroys, and it never commits a
destructive act by itself — the dustbin gesture opens a confirmation precisely because the
gesture itself is the least deliberate input the app has.
