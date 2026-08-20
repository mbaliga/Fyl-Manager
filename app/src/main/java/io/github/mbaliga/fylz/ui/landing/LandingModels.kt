package io.github.mbaliga.fylz.ui.landing

import android.net.Uri

/**
 * How the landing surface presents the folder Settings has designated as its subject.
 *
 * [LOCATIONS] is today's [io.github.mbaliga.fylz.ui.StorageHomeScreen]. [DESKTOP] -- the default
 * -- is the other mode that needs no subject: a freeform desktop of shortcuts and widgets (quick
 * access, storage, deleted files, pinned, tags, and more) rather than a reading of one chosen
 * folder. [DESKTOP] was named OVERVIEW through Build 10; the persisted preference migrates that
 * name forward (see [io.github.mbaliga.fylz.settings.AppPreferencesStore.homeMode]'s own KDoc).
 * [LIST]/[BENTO]/[CANVAS] all read the same [LandingSubject] through a different lens; switching
 * between them never touches the subject itself.
 */
enum class HomeMode {
    LOCATIONS,
    DESKTOP,
    LIST,
    BENTO,
    CANVAS,
}

/**
 * The folder LIST/BENTO/CANVAS list on the landing home, picked once in Settings.
 *
 * [treeUri] is the SAF tree grant; [folderUri] is the folder within it actually listed --
 * today always the tree's own root, kept separate from [treeUri] because a future pick of a
 * subfolder shouldn't need a new grant. [name] is captured at pick time so the subject header
 * never re-resolves a display name from a URI that may since have gone stale.
 */
data class LandingSubject(
    val treeUri: Uri,
    val folderUri: Uri,
    val name: String,
)

/**
 * Whether the cold-start hero has already shown this process.
 *
 * A process-lifetime flag, not a preference: the splash must never replay on a configuration
 * change (the Activity recreates but the process, and this object, survive it), yet is free to
 * show again after a genuine process restart. [io.github.mbaliga.fylz.ui.components.ThumbnailCache]
 * answers to the same shape of question for the same reason -- state that means nothing once the
 * process is gone doesn't belong in SharedPreferences.
 */
internal object LandingGate {
    var shownThisProcess: Boolean = false
}
