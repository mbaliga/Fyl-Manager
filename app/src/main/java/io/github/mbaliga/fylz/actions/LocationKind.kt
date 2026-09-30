package io.github.mbaliga.fylz.actions

import android.net.Uri
import io.github.mbaliga.fylz.storage.ArchiveDocumentsProvider

/**
 * What kind of place the active tab's current location is -- Addendum section C3's `location.kind`
 * value set **as far as M3.3 can tell it** (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.6).
 * Section C3 names `internal`, `sd`, `usb`, `network`, `recycle-bin`, `archive` and `disk-image`:
 *
 * - [FOLDER] is every tree location today. MC.2 refines it into `internal`/`sd`/`usb`/`network`/
 *   `recycle-bin` when it wires the condition evaluator; nothing in M3.3 needs those apart.
 * - [ARCHIVE] is a location whose Uri belongs to `ArchiveDocumentsProvider` (section C3's
 *   `archive`): read-only until M3.6, so every built-in that writes the source or the current
 *   location is disabled there through the registry.
 * - `disk-image` arrives with M4 (`.iso` browsing is `ARCHIVE` meanwhile, logged).
 */
enum class LocationKind {
    FOLDER,
    ARCHIVE,
    ;

    companion object {
        fun of(currentLocationUri: Uri?): LocationKind =
            if (ArchiveDocumentsProvider.isArchiveUri(currentLocationUri)) ARCHIVE else FOLDER
    }
}
