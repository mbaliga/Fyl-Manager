package io.github.mbaliga.fylz.staging

import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.core.model.ItemRef

/**
 * One member of the persistent Shelf.
 *
 * Enough to render a deck card and act on the item without re-probing the provider on every
 * open. [sizeBytes] and [modifiedAtMillis] double as the add-time
 * [io.github.mbaliga.fylz.core.model.VersionStamp.Composite] -- the evidence a later probe
 * compares against to notice the item changed underneath the Shelf. [sourceCrumb] is
 * display-only provenance ("Downloads › invoices"), never parsed back into a location.
 */
data class ShelfItem(
    val ref: ItemRef,
    val displayName: String,
    val kind: EntryKind,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val addedAtMillis: Long,
    val sourceCrumb: String,
)
