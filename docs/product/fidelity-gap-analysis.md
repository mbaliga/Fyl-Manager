# Fidelity gap analysis — Fylz against the reference set

**Prepared 22 Aug 2026, from the owner's side: "the app is missing a lot wrt fidelity, finesse
and feel," with four references supplied as the quality bar and theming named as the top
requirement.** The references are committed beside this file in `references/` so the bar
itself survives sessions:

| Ref | File | What it is |
|---|---|---|
| R1 | `ref-1-ios-files-folders.jpg` | iOS Files: coloured folders whose *body* carries a purpose glyph or brand mark; thumbnail cards; count captions |
| R2 | `ref-2-glass-folders-stickers.jpg` | Frosted-glass folders, documents physically peeking out of the top, one large die-cut brand sticker per folder, count chips, status ticks on the peeking sheets, segmented filter chips, floating grid/list pill |
| R3 | `ref-3-dark-subject-tiles.jpg` | Dark gallery: solid-colour folder tiles, one line-art subject glyph each (atom, book, flask), a favourite star, name + timestamp caption |
| R4 | `ref-4-material-folders.jpg` | Physical materials: felt, leather, kintsugi lacquer, cordura with a woven label, translucent plastic showing contents, wood, moulded gloss, puffer quilt — two with faces |

This document is the design gate. Nothing below is implementation; each finding names what the
code can already express, what it cannot, and the work package that would close it.

## What already exists (and matters — do not rebuild these)

The systems the references imply are mostly *present*: five themes on one axis
(`ThemeStyle`: NEO/FYLZ/VINTAGE/RETRO/CLI), four folder materials (`FolderMaterial`:
SOLID/FROSTED/ICONIC/TEXT), per-folder colour from a light/dark-paired palette
(`FolderPalette`, six-tone core plus full catalogue for the Fylz theme), per-folder stickers
(`FolderStickers`: 8 glyphs, up to 6 per folder), live folder peeks with real thumbnails
(`FolderPeek` → `FolderFace`'s frosted register), photo stacks (`StackCard`), count chips
(`CountChip`), the tactile control kit (`ui/tactile`), soft depth (`FylzDepth.softShadow`),
and Hyle's type. The gap is not missing machinery. It is that the machinery stops one step
short of what makes each reference land.

## The gaps, reference by reference

### R2 (the flagship reference — closest to the FYLZ theme, and the furthest ahead of it)

1. **Contents peek *behind* glass; the reference peeks *out of* it.** `FrostedFolderFace`
   shows thumbs through the pane. In R2 the sheets physically emerge above the folder's top
   edge, overlapped and rotated a few degrees each, and the folder body clips them from the
   waist down. That out-of-the-top silhouette is the single strongest "this folder is full of
   real things" read in the whole set, and no register can draw it today. → **WP-F1**.
2. **Stickers are identity, not decoration.** R2 uses ONE large die-cut sticker as the
   folder's identity mark, placed with intent (centred, or kissing a corner), with a paper-edge
   white keyline. Fylz stickers are 8 small abstract glyphs, up to six scattered. The
   vocabulary (bolt, moon, pin) cannot say "Figma folder". → **WP-F2**: hero-sticker slot
   (one, large, keylined) + custom sticker packs; keep the small glyphs as secondary flair.
3. **The peeking sheets carry state.** Green ticks and amber pendings sit on the sheets
   themselves. Fylz has favourites, sensitive flags and recovery states that currently render
   nowhere on a folder face. → **WP-F5**: state badges on peek thumbs (favourite star,
   sensitive shield, recovery clock), drawn on the sheet, not the folder.
4. **The finishing layer.** Count chip beside the name (exists — mount it), segmented filter
   chips, and the floating grid/list pill (a `CommandPill` cousin) — mostly assembly of
   existing parts. → **WP-F6**.

### R1

5. **The folder body can carry a purpose mark.** R1's folders read at a glance because the
   glyph is *inside* the body (rainbow flower = photos, brand marks = providers). Fylz's
   SOLID register draws colour + tab only; purpose lives in the label text. → folds into
   **WP-F2** (a hero sticker centred on the body IS this) plus **WP-F3**'s glyph vocabulary.

### R3

6. **A quiet dark register with subject glyphs.** ICONIC comes close structurally, but its
   icons are *file-type* icons; R3's are *subject* glyphs (atom, flask, book, moon) chosen per
   folder, one accent star for favourites, and a timestamp caption. → **WP-F3**: a line-art
   subject-glyph set as a per-folder appearance choice (same `FolderAppearanceStore` slot the
   colour uses), favourite star on the tile, optional modified-time caption in grid view.

### R4

7. **Materials as skins.** SOLID/FROSTED are shading models; felt, leather, wood, gloss and
   puffer are *textures with lighting*. This is a new register: image-backed material skins
   with a normal-ish highlight pass, per-folder or per-theme. It is also the heaviest item:
   texture assets fight the APK-size discipline and the no-decorative-bloat instinct.
   → **WP-F4**, explicitly behind a research gate (asset budget, licensing of textures,
   low-end GPU cost of the highlight pass). The googly eyes are character, not scope creep —
   but they come last.

### Cross-cutting finesse (every reference, no single feature)

8. The references share: large soft radii, one shadow language (low, warm, wide), captions in
   a single quiet type scale, and *restraint* — each tile says one thing. Fylz's Build 11.5+
   passes moved toward this; the remaining work is an audit pass with the screenshot renderer
   (`Build11ScreenshotRender`) against these four images, tile by tile, not a rebuild.

## Order and gates

WP-F1 → WP-F2 → WP-F5 → WP-F6 first (all inside the existing FYLZ/frosted register, no new
assets beyond sticker art), then WP-F3 (new glyph set), then WP-F4 (research gate). Every WP
lands behind the screenshot renderer so the comparison to `references/` is a rendered fact,
and the final call on feel stays where it belongs: on the owner's device.
