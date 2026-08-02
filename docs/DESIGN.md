# Interaction and visual direction

## Spatial model

Wide layouts default to three zones:

1. **Left navigation**: locations, bookmarks, saved views and tabs. It can collapse to icons and can be pinned.
2. **Centre workspace**: folder content, search, view controls and contextual operations.
3. **Right preview**: content and metadata without navigation loss.

The preview can detach into a draggable, resizable in-app floating pane. It is not an Android overlay and requests no draw-over-other-apps permission.

## View density

- Compact: one-line rows and fast scanning
- Comfortable: name plus type/size
- Generous: larger targets, richer metadata and date context

Density is independent from list/grid mode and persists locally.

## Shell modes

Traditional mode exposes a top bar and fixed navigation. Immersive mode removes permanent application chrome; pane and search controls remain reachable from the workspace toolbar. A user must never become trapped in immersive mode.

## Theme contract

The initial presets are system, light, dark, OLED and monochrome. A future theme file should be a versioned JSON token document, not arbitrary executable styling. Required tokens include field, pane, elevated pane, ink, muted ink, accent, outline, danger, selection, radius, spacing and motion.

Accessibility gates:

- 4.5:1 text contrast and 3:1 non-text contrast
- colour never carries provenance or state alone
- minimum 48 dp primary touch targets
- keyboard/mouse focus visibility
- semantic labels for icon-only controls
- layouts survive font scaling and landscape/split-screen changes
