# images/

Sizes and check commands: `Personal-Tracker/store/ASSET_SPECS.md`.

## Needed, none present yet

- `icon.png` 512x512 no alpha · `featureGraphic.png` 1024x500 no alpha
- `phoneScreenshots/` — 2 to 8, 1080x1920, no alpha.
- `tenInchScreenshots/` — **do not skip.** 1600x2560. The app's adaptive
  tablet/foldable/desktop layouts are a real differentiator, and Play down-ranks
  large-screen-capable apps that ship no large-screen shots.

## Shoot these

Phone: two folder tabs open, the details view, the recovery/journal screen mid-operation.
Tablet: the docked preview pane beside a multi-column listing. That frame is the
one that shows this is a workspace and not another file browser.

## Also needed, separately from screenshots

The **All Files Access demo video** for the permission declaration. One take,
around two minutes: launch straight into full storage with no picker, perform a
cross-volume move, restore a file from the recycle bin. Show the function, not a
UI tour. See `docs/store/play-console.md`.

**Check every frame for real personal file names** before uploading. This is the
app most likely to leak something private through a screenshot; use a prepared
folder tree, not your actual storage.

```sh
adb exec-out screencap -p > shot.png
magick shot.png -background black -alpha remove -alpha off phoneScreenshots/01.png
```
