package io.github.mbaliga.fylz.ui.components

import io.github.mbaliga.fylz.model.FileEntry

/** Folder-card redesign (Figma specs: `docs/agent/PROGRESS.md`): default count of files a folder
 * card previews through its glass panel. */
const val FOLDER_PREVIEW_LIMIT: Int = 4

/**
 * Pure selection logic for the folder card's live preview: which of a folder's children get
 * thumbnailed through the glass panel. Kept as a standalone function with no I/O and no Compose
 * dependency -- see `FolderPreviewPickerTest` -- so it is unit-testable on its own, independent of
 * how [entries] was fetched, capped or cached.
 *
 * Most-recently-modified first ([FileEntry.lastModifiedMillis] descending); a null timestamp
 * (never recorded by a document provider) sorts after every known one rather than before it --
 * `sortedByDescending` on a nullable `Long` already does this (ascending `compareValues` treats
 * null as the smallest value, so reversing that order for "descending" pushes it last), so no
 * custom comparator is needed. The sort is also stable, so entries that tie (same timestamp, or
 * both null) keep [entries]' own relative order rather than an arbitrary one.
 *
 * Judgment call: directories in [entries] are always skipped, even though the caller's listing may
 * include them. The preview is a "recent files" sneak peek through the glass; a subfolder has no
 * file content of its own to show through it -- `EntryThumbnail` falls back to a plain folder
 * vector icon for one -- so including it would be clutter standing in for a real thumbnail, not
 * information.
 */
fun pickFolderPreviewFiles(entries: List<FileEntry>, limit: Int = FOLDER_PREVIEW_LIMIT): List<FileEntry> =
    entries
        .asSequence()
        .filterNot { it.isDirectory }
        .sortedByDescending { it.lastModifiedMillis }
        .take(limit)
        .toList()
