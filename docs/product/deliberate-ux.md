# Deliberate actions

**Owner direction (23 Aug 2026).** A mechanical button never fired under a resting finger: its
actuation force made every press a decision. Capacitive glass removed that threshold, and the
cost is the accidental tap — the wrong file recycled, the wrong sheet confirmed, by a touch
that was never a choice. The nearest touch-native equivalent of actuation force is the swipe:
not infallible, but commitment expressed as *distance held under way*, which a stray contact
does not produce.

## The setting

`Settings → Gestures → Deliberate actions`, off by default (deliberation is a choice, not a
toll), stored as `AppPreferencesStore.deliberateActions`. When on, destructive confirmations
swap their tap button for `ui/tactile/SlideToConfirm`: thumb tracks the finger 1:1, release
past 85% of the track commits exactly once, anything less settles back in ~320ms eased with no
spring (the constellation motion contract). Accessibility does not inherit the friction: the
control's semantics carry an ordinary confirm click, because an assistive-tech activation is
already deliberate.

## Where it applies

Applied now: the Shred confirmation (`ShredConfirmOverlay`), the single most destructive
surface after the typed-phrase permanent delete (which already has stronger friction and keeps
it). To adopt next, in order of accident cost: guarded replacement in conflict resolution,
"Remove missing" and bulk restore in the Recycle Bin, and backup-plan deletion. Each adoption
is one `deliberate` parameter and one branch, following the ShredConfirmOverlay pattern.

## The Hyle question

This belongs in the design system eventually — the owner's direction names Hyle explicitly, so
every constellation app can offer the same mode with the same physics. The promotion path:
`SlideToConfirm` moves to Hyle's tactile kit once its geometry has been device-felt in Fylz,
taking the ~320ms/no-spring contract and the accessibility bypass with it, and Fylz's copy
becomes a re-export. Promoting it before it has been felt on hardware would freeze the wrong
numbers into a shared contract — Fylz is the proving ground first.
