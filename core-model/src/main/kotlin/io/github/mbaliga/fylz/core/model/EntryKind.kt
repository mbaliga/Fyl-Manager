package io.github.mbaliga.fylz.core.model

/**
 * What kind of thing an item is, at the coarsest grain the app reasons about.
 *
 * Relocated here (WP-1.3) from `io.github.mbaliga.fylz.model` because both [ItemSnapshot] and
 * `core-format`'s `FileFormatRegistry` need it and neither may depend on the other — this is
 * the shared foundation. The app module re-exports nothing; every former consumer imports this
 * package directly.
 */
enum class EntryKind {
    DIRECTORY,
    MARKDOWN,
    TEXT,
    IMAGE,
    PDF,
    ARCHIVE,
    AUDIO,
    VIDEO,
    OTHER,
}
