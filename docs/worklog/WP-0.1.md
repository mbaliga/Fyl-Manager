# WP-0.1 — Unblock GitHub Actions (owner action)

**Status: appears RESOLVED — verified from the outside, 9 Aug 2026 (session of PR #14).**

The plan describes runs queueing ~15 minutes then cancelling without starting, across repos,
and attributes it to an account-level constraint (likely the Actions spending cap). Whatever
the cause was, it is no longer biting: on 9 Aug 2026 at 23:07 UTC both workflows ran to
completion on PR #14 —

- `Android CI / Test, lint, and build debug APK` — success in ~4.5 min
- `Release readiness / Validate release candidate` — success in ~8 min

No Claude-side change was involved; presumably the owner adjusted billing, or the constraint
lapsed. **Residual owner action:** none required while runs keep starting. If queued-then-
cancelled reappears, check Settings → Billing → Actions spending limit per the plan.

Consequence for the rest of Phase 0: PRs no longer need the "CI pending unblock" flag; CI
results are real gates again.
