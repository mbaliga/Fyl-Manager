# WP-0.8 — Device-test script for Build 3 (owner action prepared)

**Status: document DONE; execution is Madhav's, on hardware.**

`docs/DEVICE-TEST-BUILD3.md` is the structured checklist: setup, room reveal gesture per edge
(1:1 tracking, release-decides, no spring, mid-flight reversal, gesture-nav conflict probes,
corner honesty), the way back (tap/drag/back-button/through-home-to-opposite-room), the details
room's tree and facts, the actions room's conditional rows, the sort-keyed scrubber under all
four sorts (including the date-named-logs `#` bucket), shake-to-refresh, the one-theme check
across Tools/Index/Recovery, the crash screen, and all-files-access grant/return detection.

**Deviation from the plan's letter:** the plan describes testing "the three rooms"; the branch
under test now has four (PR #14 added details TOP and actions BOTTOM). The checklist covers the
four-room state because that is what the next build on this branch will contain — testing the
plan's snapshot instead of the shipping state would validate a build nobody installs.

**Next:** owner runs it on device; findings file into Phase 2 (WP-2.1 gesture-safe seams
especially — item 1.7's gesture-nav conflict count is the direct input).
