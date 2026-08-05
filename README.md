# Fylz — installable test build (2026-08-05, build 3)

Not for distribution. Debug-key signed, arm64-v8a only. Built from `cf8bfba`.

**Direct download:** [`fylz.apk`](https://github.com/mbaliga/fyl-manager/raw/apk-testbuild/fylz.apk)

Build 3 — the UX and fidelity pass:

- **The fonebrew navigation.** Three rooms: open locations on the left, tools and
  settings on the right, recovery below. The Files/Recovery bottom tabs, the numbered
  workspace chips and the eleven-item overflow menu are all gone. The top edge is
  reserved; nothing claims the pull-down.
- **Niagara-style edge scrubber.** Glide a finger down the right edge to sweep the
  listing. Its labels follow whatever the list is sorted by — letters, months, size
  bands or file types.
- **The light Tools screen is fixed at its cause.** Tools, the index manager, recovery
  and the activity wrapper were all painting under a bare Material theme instead of
  Fylz's. They render in the app's own colours now.
- **The crash screen looks and behaves properly** — the clipped mark and the empty
  button are fixed, buttons answer a press, and it arrives in Fylz's moss green.
- Refresh is a shake (the toolbar button stays).

Same package as build 2 (`io.github.mbaliga.fylz`), so it updates in place.
Delete this branch with `git push origin --delete apk-testbuild` when done.
