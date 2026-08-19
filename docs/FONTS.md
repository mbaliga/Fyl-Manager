# Bundled fonts

Fylz bundles its own type rather than relying on whatever sans the device ships, the same way it
bundles its own icon artwork. Two families are in `app/src/main/res/font/` today.

## Hyle Deco Pro (`hyle_deco_pro_bold.ttf`)

The landing wordmark's face, used nowhere else in the app (see `LandingSplash.kt`). Dual-licensed:
the DejaVu Sans / Bitstream Vera chassis it sits on carries the Bitstream Vera licence, and the
Hyle Deco letterforms spliced into that chassis carry the SIL Open Font License 1.1. Both notices
apply; see `hyle-design-system/fonts/HyleDecoPro/LICENSE-NOTE.txt` in the design-system submodule
for the full text. Because it isn't OFL-only, it isn't eligible for the Google Fonts catalog, but
it is free to use and redistribute under both licences.

## Space Grotesk (`space_grotesk_regular.ttf`, `space_grotesk_medium.ttf`, `space_grotesk_bold.ttf`)

The app's own type family (`FylzTheme.kt`'s `fylzTypography`) and the bottom chrome's literal
Figma read (tab band, selection row, actions bar) alike. Sourced from the Google Fonts catalog at
weights 400/500/700, licensed under the **SIL Open Font License, Version 1.1** — OFL-only, no
second licence to track. The OFL permits bundling, embedding and redistribution, including inside
this app's own APK, without a separate attribution file shipped to end users; this note is that
record for the repository.
