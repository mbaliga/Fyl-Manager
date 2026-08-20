package io.github.mbaliga.fylz.intents

import android.content.Intent
import android.net.Uri

/**
 * The intent door: every launcher-surface entry point (app widgets, static shortcuts, and any
 * future assistant/deep-link route) that wants to hand [io.github.mbaliga.fylz.MainActivity] a
 * command speaks through these actions and extras -- never a bespoke one-off action string.
 *
 * [parse] is the single place that reads them back off an [Intent] into a [FylzCommand].
 * Deliberately total: it never throws, and any action it does not recognise -- including a null
 * action, a platform action like [Intent.ACTION_VIEW], or [ACTION_OPEN_FOLDER] missing its
 * required [EXTRA_TREE_URI] -- returns `null` rather than guessing. Callers (MainActivity's own
 * intent handling, owned by another workstream) treat `null` as "nothing to do."
 */
object FylzIntents {
    const val ACTION_SCAN = "io.github.mbaliga.fylz.action.SCAN"
    const val ACTION_SEARCH = "io.github.mbaliga.fylz.action.SEARCH"
    const val ACTION_OPEN_SHELF = "io.github.mbaliga.fylz.action.OPEN_SHELF"
    const val ACTION_OPEN_TRASH = "io.github.mbaliga.fylz.action.OPEN_TRASH"
    const val ACTION_OPEN_FOLDER = "io.github.mbaliga.fylz.action.OPEN_FOLDER"

    const val EXTRA_TREE_URI = "io.github.mbaliga.fylz.extra.TREE_URI"
    const val EXTRA_FOLDER_URI = "io.github.mbaliga.fylz.extra.FOLDER_URI"

    fun parse(intent: Intent): FylzCommand? = when (intent.action) {
        ACTION_SCAN -> FylzCommand.Scan
        ACTION_SEARCH -> FylzCommand.FocusSearch
        ACTION_OPEN_SHELF -> FylzCommand.OpenShelf
        ACTION_OPEN_TRASH -> FylzCommand.OpenTrash
        ACTION_OPEN_FOLDER -> parseOpenFolder(intent)
        else -> null
    }

    private fun parseOpenFolder(intent: Intent): FylzCommand.OpenFolder? {
        val treeUri = intent.getStringExtra(EXTRA_TREE_URI)?.let(Uri::parse) ?: return null
        val folderUri = intent.getStringExtra(EXTRA_FOLDER_URI)?.let(Uri::parse)
        return FylzCommand.OpenFolder(treeUri, folderUri)
    }

    /** Stamps [ACTION_OPEN_FOLDER]'s action and extras onto an already-targeted [Intent] (built
     *  by the caller as `Intent(context, MainActivity::class.java)`), so widgets, shortcuts and
     *  any future caller never spell [EXTRA_TREE_URI] / [EXTRA_FOLDER_URI] by hand. */
    fun applyOpenFolderExtras(intent: Intent, treeUri: Uri, folderUri: Uri?): Intent = intent
        .setAction(ACTION_OPEN_FOLDER)
        .putExtra(EXTRA_TREE_URI, treeUri.toString())
        .apply { folderUri?.let { putExtra(EXTRA_FOLDER_URI, it.toString()) } }
}

/** What a launcher-surface intent asked Fylz to do, decoded from an [Intent] by [FylzIntents.parse]. */
sealed interface FylzCommand {
    object Scan : FylzCommand
    object FocusSearch : FylzCommand
    object OpenShelf : FylzCommand
    object OpenTrash : FylzCommand
    data class OpenFolder(val treeUri: Uri, val folderUri: Uri?) : FylzCommand
}
