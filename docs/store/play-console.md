# Fylz — Play Console answer sheet

> Only the **deltas** from `Personal-Tracker/store/HOUSE_DEFAULTS.md`.

| | |
|---|---|
| applicationId | `io.github.mbaliga.fylz` |
| Version at time of writing | `1.0.0-alpha01` (versionCode `1`) |
| Category | **Tools** |
| Tags | file manager, files, storage, tablet, foldable, local first |
| Contact email | `fylz@asystemofcells.com` |
| Website | `https://asystemofcells.com/fylz` |
| Privacy policy | `https://asystemofcells.com/fylz/privacy` |

> Two open items from `NAMES.md` and `CONSTELLATION.md`:
> **the display name** is inconsistent ("Fylz" vs "Fyl Manager"); this sheet settles
> on **Fylz**, which is what the app itself uses. And **PR #7 is CI red** on
> duplicate class redeclarations, with scope still creeping. There is no shippable
> build until that is green.

## The one thing that will decide this release: All Files Access

`MANAGE_EXTERNAL_STORAGE` requires a **declaration form plus a demo video**, and it
is the single most common reason a file manager sits in review for weeks.

**You are in a strong position, so make the strong argument.** Google names *file
managers* as an eligible category for this permission. The declaration should say,
plainly:

> This is a general-purpose file manager. Its core function is letting the user
> browse, move, copy, rename and recover files across their entire device storage.
> The Storage Access Framework cannot serve that function: it grants access only to
> user-selected trees, cannot enumerate all storage volumes, and cannot perform
> cross-volume moves or maintain a durable multi-file operation journal, all of
> which are core features here.

**The video** should show, in one take: launching to full storage with no picker, a
cross-volume move, and the recycle bin restoring a file. Show the *function*, not a
UI tour. Two minutes is plenty.

Also true and worth stating: the app **also** supports SAF for cloud, USB and
third-party providers, with persisted grants. Using both is not a contradiction;
it is the correct design, and saying so pre-empts the obvious reviewer question.

## Deltas from the house defaults

### Data safety
**No data collected. No data shared.**

| Question | Answer |
|---|---|
| Collect or share any user data? | **No** |
| Encrypted in transit? | Yes |
| Deletion? | Users can delete data in the app |

The claim to hold to: **file contents and names are never uploaded, remotely
indexed or scanned.** The app has `INTERNET`, and a reviewer will ask what for. The
honest answer is the optional bring-your-own-key AI organisation feature, and
remote providers (SMB, SFTP, S3) the user configures. Both are user-initiated
transfers to destinations the user chose. Neither is collection by this app.

> ⚠️ **If the AI-organisation feature sends file names or contents to a provider,
> the privacy policy must say so in those words.** It currently does. Keep it
> accurate as that feature changes; this is the kind of thing that turns into a
> removal rather than a rejection.

### Permissions
| Permission | Why | Play form? |
|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | The product. See above. | **Yes: declaration + video.** |
| `INTERNET` | Remote providers and optional BYO-key AI. | No |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Long copy and move operations that must survive backgrounding. `dataSync` is the right type and justifies easily. | Justification text |
| `VIBRATE` | Haptic feedback on destructive confirmations. | No |

### Content rating
- Category `Utility, Productivity, Communication, or Other`.
- **"Unrestricted internet browsing"** → **No.** A file manager is not a browser,
  even one that previews files.
- Everything else No. Expected **Everyone**.

### Large screens — do not skip this
The app claims tablet, foldable and desktop-mode support, and that is a genuine
differentiator. Play down-ranks large-screen-capable apps with no large-screen
screenshots, and this app would be competing without its best argument.
**Ship `tenInchScreenshots/`.**

## F-Droid
- ✅ Licence present. Declare **`NonFreeNet`** (optional BYO-key AI and remote
  providers).

## Pre-submit checklist

- [ ] PR #7 CI green. There is no build to ship until then.
- [ ] Settle the display name: **Fylz**.
- [ ] Record the All Files Access demo video.
- [ ] Write the declaration using the wording above.
- [ ] `tenInchScreenshots/` captured, not just phone.
- [ ] Confirm the recycle bin genuinely never auto-expires. It is stated in the
      listing and in the app's own spec as a hard rule.
