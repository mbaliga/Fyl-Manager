package io.github.mbaliga.fylz.core.format

/**
 * The plan's preview capability ladder: "every file is inspectable, not every file gets a full
 * preview." Six rungs, each one strictly weaker as a claim than the one above it:
 *
 * - [IDENTITY] — magic-byte/claimed-type comparison, hashes, extracted strings, a hex dump,
 *   an entropy estimate. The universal fallback: nothing may ever fail to reach at least this.
 * - [METADATA] — provider-reported size, timestamps, MIME, and (for containers) an entry list,
 *   without opening the content itself.
 * - [STRUCTURED] — the format is parsed well enough to answer structural questions (an
 *   archive's entries, a database's tables) without a full semantic render.
 * - [INSPECTED] — bounded, sandboxed content inspection beyond structure: partial decode,
 *   bounded text extraction, safety-limited parsing of untrusted input.
 * - [RENDERED] — the format's own visual/semantic form (an image shown as an image, text shown
 *   as text, a PDF page rendered).
 * - [EDITABLE] — the format can be safely round-tripped: read, changed, and written back
 *   without silently corrupting or reformatting what the edit didn't touch.
 *
 * ### This is scaffolding, not the finished ladder
 *
 * WP-1.3 introduces the type because `core-format` is where the plan says it belongs; it does
 * NOT yet assign a level to every format `FileFormatRegistry` knows about. That per-handler
 * audit — "every existing handler in `preview/` declares its honest level" — is WP-3.6's job.
 * [FileFormatDescriptor.provisionalLevel] gives a conservative, mechanical default derived from
 * the existing [PreviewDepth] so callers have *something* today; treat it as a placeholder, not
 * a verified claim about any specific renderer's actual behavior.
 */
enum class PreviewLevel {
    IDENTITY,
    METADATA,
    STRUCTURED,
    INSPECTED,
    RENDERED,
    EDITABLE,
}

/**
 * A conservative, provisional [PreviewLevel] derived from this descriptor's [PreviewDepth].
 *
 * The mapping is deliberately lossy and deliberately conservative: [PreviewDepth.RENDERED]
 * maps to [PreviewLevel.RENDERED], never [PreviewLevel.EDITABLE] — nothing in this app's
 * current preview routing promises a round-trippable edit, so nothing is claimed to. This
 * exists so `core-format` has *a* level to offer before WP-3.6's real, per-handler audit
 * replaces it with a verified one; do not treat its output as that audit's result.
 */
val FileFormatDescriptor.provisionalLevel: PreviewLevel
    get() = when (depth) {
        PreviewDepth.RENDERED -> PreviewLevel.RENDERED
        PreviewDepth.STRUCTURED -> PreviewLevel.STRUCTURED
        PreviewDepth.INSPECTED -> PreviewLevel.INSPECTED
    }
