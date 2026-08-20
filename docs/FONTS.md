# Bundled fonts

Fylz bundles its own type rather than relying on whatever sans the device ships, the same way it
bundles its own icon artwork. Two families are in `app/src/main/res/font/` today, and both are
genuinely Hyle fonts — the owner's Build 11.5 review flagged that this wasn't true before (see
"Space Grotesk, removed" below) and asked for it to be fixed.

## Hyle Grotesk Classic (`hyle_grotesk_classic_light.ttf`, `_regular.ttf`, `_medium.ttf`,
`_bold.ttf`)

The app's own type family (`FylzTheme.kt`'s `fylzTypography`) and the bottom chrome's literal
Figma read (`ChromeTokens.kt`'s `chromeFontFamily()`, tab band / selection row / actions bar)
alike. Sourced from `hyle-design-system/fonts/HyleGroteskClassic/fonts/ttf/` in this repo's own
design-system submodule at four weights (Light/Regular/Medium/Bold), licensed under the **SIL Open
Font License, Version 1.1**. Per
`hyle-design-system/fonts/HyleGroteskClassic/LICENSE-NOTE.txt`: it is built on Space Grotesk with
Archivo letterforms substituted — a derivative work, which is exactly why it isn't eligible for
the Google Fonts catalog itself, but it is free to use, modify and redistribute under the OFL, and
it *is* a Hyle-native face rather than an outside import riding under a Hyle-adjacent name. The OFL
permits bundling, embedding and redistribution, including inside this app's own APK, without a
separate attribution file shipped to end users; this note is that record for the repository.

Because Hyle Grotesk Classic keeps Space Grotesk's geometric letterforms as its chassis, the
chrome keeps the same geometry-family look it always had — only the font resource backing it
changed, not the tab band's shape, the selection pill, or any colour.

## Hyle Deco Pro (`hyle_deco_pro_bold.ttf`)

The landing wordmark's face, used nowhere else in the app (see `LandingSplash.kt`). Unchanged by
Build 11.5. Dual-licensed: the DejaVu Sans / Bitstream Vera chassis it sits on carries the
Bitstream Vera licence, and the Hyle Deco letterforms spliced into that chassis carry the SIL Open
Font License 1.1. Both notices apply; see `hyle-design-system/fonts/HyleDecoPro/LICENSE-NOTE.txt`
in the design-system submodule for the full text. Because it isn't OFL-only, it isn't eligible for
the Google Fonts catalog, but it is free to use and redistribute under both licences.

## Space Grotesk, removed

Space Grotesk (`space_grotesk_regular.ttf`, `_medium.ttf`, `_bold.ttf`) shipped through Build 11
as both the app's own type family and the chrome's font. It is stock upstream Space Grotesk from
the Google Fonts catalog — a real font, OFL-licensed, but not a Hyle font: it carries no Hyle
letterform work of its own, just an outside import that happened to look geometric enough to sit
next to the rest of the Hyle-flavoured UI. The owner's Build 11.5 review ("I hope you are only
using Hyle fonts") named this directly, and it's a fair catch: nothing in the app before this pass
was actually a Hyle typeface. Hyle Grotesk Classic (above) replaces it everywhere — same
geometric-grotesk register, but genuinely Hyle's own derivative face rather than an unmodified
outside import. The three `space_grotesk_*.ttf` files have been deleted from
`app/src/main/res/font/`; nothing in `app/src` should reference `R.font.space_grotesk_*` after
this change.
