package io.github.mbaliga.fylz.search

import android.net.Uri
import dev.aarso.search.CoverageRecencyScorer
import dev.aarso.search.DocId
import dev.aarso.search.EvalContext
import dev.aarso.search.FacetField
import dev.aarso.search.FacetValueKind
import dev.aarso.search.FieldName
import dev.aarso.search.FieldRegistry
import dev.aarso.search.Matcher
import dev.aarso.search.NaturalQuery
import dev.aarso.search.NaturalVocabulary
import dev.aarso.search.Normalizer
import dev.aarso.search.Op
import dev.aarso.search.ParsedQuery
import dev.aarso.search.QueryCompiler
import dev.aarso.search.QueryNode
import dev.aarso.search.RANKING_ORDER
import dev.aarso.search.RelativeDate
import dev.aarso.search.SearchDoc
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import java.time.ZoneId

/**
 * Fylz's one point of contact with `search-core`: the field vocabulary over [FileEntry], the
 * natural-language synonyms [NaturalQuery] rewrites against, and the scorer wiring both the
 * in-folder browser and `RecursiveSearchEngine` rank with.
 *
 * Nothing outside this file should build a [FieldRegistry] or a [Matcher] over [FileEntry] --
 * that would be a second definition of what `type:photo` or "large videos" means, free to drift
 * from this one.
 */
object FylzSearch {

    // ---- kind vocabulary -----------------------------------------------------------------

    /**
     * Canonical `type:` value per synonym, shared by the structured facet ([TypeFacet]) and the
     * natural-language layer ([vocabulary]) so a spelling one recognizes, the other does too.
     * Carries the pre-search-core alias table forward unchanged, plus `document` and
     * `screenshot` -- neither is a real [EntryKind] of its own, so [kindMatches] resolves them
     * against a set of kinds and a name check respectively rather than a 1:1 enum lookup.
     */
    private val KIND_ALIASES: Map<String, String> = mapOf(
        "folder" to "folder", "dir" to "folder", "directory" to "folder",
        "image" to "image", "photo" to "image", "picture" to "image",
        "video" to "video", "movie" to "video",
        "audio" to "audio", "music" to "audio", "sound" to "audio",
        "pdf" to "pdf",
        "archive" to "archive", "zip" to "archive",
        "text" to "text", "txt" to "text",
        "markdown" to "markdown", "md" to "markdown",
        "document" to "document", "doc" to "document",
        "screenshot" to "screenshot",
    )

    /** No dedicated `EntryKind` exists for word-processing/text documents; PDF, plain text and
     *  markdown are the three kinds a person means by "my documents" here. */
    private val DOCUMENT_KINDS = setOf(EntryKind.TEXT, EntryKind.MARKDOWN, EntryKind.PDF)

    /**
     * [NaturalVocabulary] folds a trailing `s` on both sides of a lookup, so most entries below
     * need only their collective/plural spelling to also match the singular. `directories` is
     * listed explicitly because stripping its trailing `s` gives `directorie`, not `directory` --
     * the one irregular plural in this set.
     */
    val vocabulary: NaturalVocabulary = NaturalVocabulary(
        kinds = KIND_ALIASES + mapOf(
            "photos" to "image", "pictures" to "image", "images" to "image",
            "videos" to "video", "movies" to "video", "clips" to "video", "clip" to "video",
            "songs" to "audio",
            "pdfs" to "pdf",
            "docs" to "document", "documents" to "document",
            "archives" to "archive", "zips" to "archive",
            "folders" to "folder", "directories" to "folder",
            "screenshots" to "screenshot",
        ),
    )

    private fun canonicalKind(value: String): String? = KIND_ALIASES[value.trim().lowercase()]

    private fun kindMatches(entry: FileEntry, canonical: String): Boolean = when (canonical) {
        "folder" -> entry.kind == EntryKind.DIRECTORY
        "image" -> entry.kind == EntryKind.IMAGE
        "video" -> entry.kind == EntryKind.VIDEO
        "audio" -> entry.kind == EntryKind.AUDIO
        "pdf" -> entry.kind == EntryKind.PDF
        "archive" -> entry.kind == EntryKind.ARCHIVE
        "text" -> entry.kind == EntryKind.TEXT
        "markdown" -> entry.kind == EntryKind.MARKDOWN
        "document" -> entry.kind in DOCUMENT_KINDS
        "screenshot" -> entry.kind == EntryKind.IMAGE && entry.name.contains("screenshot", ignoreCase = true)
        else -> false
    }

    // ---- size units ------------------------------------------------------------------------

    private val SIZE_PATTERN = Regex("^(\\d+(?:\\.\\d+)?)\\s*([a-z]{0,3})$", RegexOption.IGNORE_CASE)
    private val SIZE_UNIT_MULTIPLIERS = mapOf(
        "b" to 1L,
        "kb" to 1_024L, "kib" to 1_024L,
        "mb" to 1_024L * 1_024, "mib" to 1_024L * 1_024,
        "gb" to 1_024L * 1_024 * 1_024, "gib" to 1_024L * 1_024 * 1_024,
    )

    private fun parseSizeBytes(raw: String): Long? {
        val match = SIZE_PATTERN.matchEntire(raw.trim()) ?: return null
        val (numberText, unitText) = match.destructured
        val multiplier = SIZE_UNIT_MULTIPLIERS[unitText.lowercase().ifEmpty { "b" }] ?: return null
        return (numberText.toDouble() * multiplier).toLong()
    }

    /** `isBacked` for the two ordered facets ([SizeFacet], [ModifiedFacet]): a range reaches a
     *  facet's `isBacked` as one unsplit `"low..high"` string -- [FacetEvaluator] only decomposes
     *  a range for [FacetField.matches], not for the backing check -- so both halves have to
     *  check out before the whole value is honestly called backed. */
    private fun isBackedRange(value: String, singleBacked: (String) -> Boolean): Boolean =
        value.split(QueryNode.Facet.RANGE_SEPARATOR, limit = 2).all { singleBacked(it.trim()) }

    /** Stand-in clock for a backing check that only asks "is this shaped like a date", never
     *  "is this a date that already happened" -- every named token here resolves to some range
     *  independent of which instant "now" actually is. */
    private val EPOCH_CTX = EvalContext(nowMillis = 0L)

    // ---- facet fields ------------------------------------------------------------------------

    private object ExtFacet : FacetField<FileEntry> {
        override val key = "ext"
        override val aliases = setOf("extension")
        override val valueKind = FacetValueKind.TEXT
        override fun isBacked(value: String) = wantedExtensions(value).isNotEmpty()
        override fun matches(subject: FileEntry, op: Op, value: String, ctx: EvalContext): Boolean {
            val wanted = wantedExtensions(value)
            if (wanted.isEmpty()) return op == Op.NE
            val has = FileFormatRegistry.compoundExtension(subject.name) in wanted
            return if (op == Op.NE) !has else has
        }

        /** `ext:pdf,png` is one facet value carrying a comma list, preserved from the
         *  pre-search-core grammar rather than requiring `ext:pdf OR ext:png`. */
        private fun wantedExtensions(value: String): Set<String> =
            value.split(',').map { it.trim().removePrefix(".").lowercase() }
                .filterTo(mutableSetOf()) { it.isNotEmpty() }
    }

    private object TypeFacet : FacetField<FileEntry> {
        override val key = "type"
        override val aliases = setOf("kind")
        override val valueKind = FacetValueKind.ENUM
        override fun isBacked(value: String) = canonicalKind(value) != null
        override fun matches(subject: FileEntry, op: Op, value: String, ctx: EvalContext): Boolean {
            val canonical = canonicalKind(value) ?: return false
            val result = kindMatches(subject, canonical)
            return if (op == Op.NE) !result else result
        }
    }

    private object SizeFacet : FacetField<FileEntry> {
        override val key = "size"
        override val valueKind = FacetValueKind.NUMBER
        override fun isBacked(value: String) = isBackedRange(value) { parseSizeBytes(it) != null }
        override fun matches(subject: FileEntry, op: Op, value: String, ctx: EvalContext): Boolean {
            val bound = parseSizeBytes(value) ?: return false
            // Unknown size can satisfy no bound -- it is neither above nor below anything, and
            // treating it as a universal `!=` match would surface files search never measured.
            val actual = subject.sizeBytes ?: return false
            return when (op) {
                Op.EQ -> actual == bound
                Op.NE -> actual != bound
                Op.GT -> actual > bound
                Op.GTE -> actual >= bound
                Op.LT -> actual < bound
                Op.LTE -> actual <= bound
            }
        }
    }

    private class ModifiedFacet(private val zone: ZoneId) : FacetField<FileEntry> {
        override val key = "modified"
        override val aliases = setOf("date", "when")
        override val valueKind = FacetValueKind.DATE
        override fun isBacked(value: String) =
            isBackedRange(value) { RelativeDate.resolveRange(it, EPOCH_CTX, zone) != null }

        override fun matches(subject: FileEntry, op: Op, value: String, ctx: EvalContext): Boolean {
            val millis = subject.lastModifiedMillis ?: return false
            val range = RelativeDate.resolveRange(value, ctx, zone) ?: return false
            // GTE/LTE mirror search-core's own "during" convention: GTE is at-or-after the
            // period's start, LTE is strictly before its end, so `>=last-week` and `<=last-week`
            // together tile the week with no gap and no overlap -- see Evaluator.kt's KDoc on
            // range decomposition. GT/LT step one whole period further, for a bare `>`/`<` typed
            // against a single date directly rather than the since/before phrases the
            // natural-language layer emits as GTE/LT.
            return when (op) {
                Op.EQ -> millis in range
                Op.NE -> millis !in range
                Op.GTE -> millis >= range.startInclusiveMillis
                Op.LTE -> millis < range.endExclusiveMillis
                Op.GT -> millis >= range.endExclusiveMillis
                Op.LT -> millis < range.startInclusiveMillis
            }
        }
    }

    private object NameFacet : FacetField<FileEntry> {
        override val key = "name"
        override val valueKind = FacetValueKind.TEXT
        override fun isBacked(value: String) = value.isNotBlank()
        override fun matches(subject: FileEntry, op: Op, value: String, ctx: EvalContext): Boolean {
            val has = subject.name.contains(value, ignoreCase = true)
            return if (op == Op.NE) !has else has
        }
    }

    private class TagFacet(private val tagsFor: (Uri) -> Set<String>) : FacetField<FileEntry> {
        override val key = "tag"
        override val valueKind = FacetValueKind.TEXT
        override fun isBacked(value: String) = value.isNotBlank()
        override fun matches(subject: FileEntry, op: Op, value: String, ctx: EvalContext): Boolean {
            val has = tagsFor(subject.uri).any { it.equals(value.trim(), ignoreCase = true) }
            return if (op == Op.NE) !has else has
        }
    }

    /**
     * A marker, not a filter: whether a hit's *content* gets read is decided by [wantsContent]
     * outside this synchronous, I/O-free predicate ([Matcher] runs no I/O by contract). Every
     * subject passes the facet itself, so the flag never silently excludes anything from the
     * metadata/name pass that finds `content:` candidates in the first place.
     */
    private object ContentFacet : FacetField<FileEntry> {
        override val key = "content"
        override val valueKind = FacetValueKind.BOOL
        override fun isBacked(value: String) = true
        override fun matches(subject: FileEntry, op: Op, value: String, ctx: EvalContext) = true
    }

    private object IsFacet : FacetField<FileEntry> {
        private val FOLDER_WORDS = setOf("folder", "dir", "directory")
        override val key = "is"
        override val valueKind = FacetValueKind.ENUM
        override fun isBacked(value: String): Boolean {
            val v = value.trim().lowercase()
            return v in FOLDER_WORDS || v == "file"
        }
        override fun matches(subject: FileEntry, op: Op, value: String, ctx: EvalContext): Boolean {
            val wantsFolder = when (value.trim().lowercase()) {
                in FOLDER_WORDS -> true
                "file" -> false
                else -> return false
            }
            val result = subject.isDirectory == wantsFolder
            return if (op == Op.NE) !result else result
        }
    }

    // ---- registry / matcher / parse -----------------------------------------------------------

    private val FIELD_NAME = FieldName("name")
    private val FIELD_PATH = FieldName("path")

    /** The full facet vocabulary. [tagsFor] backs [TagFacet] and is the only field whose real
     *  behaviour depends on it; [parse] never evaluates a facet (only recognizes and validates
     *  its shape), so it builds a registry with a no-op [tagsFor] and still recognizes, and
     *  diagnoses, every `tag:` a user types. */
    fun registry(
        zone: ZoneId = ZoneId.systemDefault(),
        tagsFor: (Uri) -> Set<String> = { emptySet() },
    ): FieldRegistry<FileEntry> = FieldRegistry(
        listOf(
            ExtFacet, TypeFacet, SizeFacet, ModifiedFacet(zone), NameFacet,
            TagFacet(tagsFor), ContentFacet, IsFacet,
        ),
    )

    /** Free text to [ParsedQuery], natural language first -- see [NaturalQuery.interpret]. */
    fun parse(raw: String, nowMillis: Long, zone: ZoneId): ParsedQuery =
        NaturalQuery.interpret(raw, vocabulary, registry(zone), EvalContext(nowMillis), zone)

    /** A [Matcher] over the same vocabulary [parse] validates against, wired to
     *  [CoverageRecencyScorer] with `name` weighted above `path` -- a hit whose own name matches
     *  should always outrank one that only matches by way of the folder it sits in. */
    fun matcher(
        zone: ZoneId = ZoneId.systemDefault(),
        tagsFor: (Uri) -> Set<String> = { emptySet() },
    ): Matcher<FileEntry> = Matcher(
        registry = registry(zone, tagsFor),
        scorer = CoverageRecencyScorer(fieldWeights = mapOf(FIELD_NAME to 2.0, FIELD_PATH to 0.5)),
    )

    /** Projects [entry] into the [SearchDoc] every matcher/scorer call here reads. [path] is the
     *  entry's location relative to a search root, when the caller has one -- the recursive walk
     *  does; an in-folder listing doesn't need one, since every entry there shares one folder. */
    fun toDoc(entry: FileEntry, path: String? = null): SearchDoc = SearchDoc(
        id = DocId(entry.uri.toString()),
        timestampMillis = entry.lastModifiedMillis ?: 0L,
        text = buildMap {
            put(FIELD_NAME, entry.name)
            if (path != null) put(FIELD_PATH, path)
        },
        display = mapOf(FIELD_NAME to entry.name),
    )

    /** One ranked in-folder result: [entry] matched [parse]'s query, scored by [matcher], with
     *  [nameHighlights] in [entry]'s own name coordinates for bolding a hit in place. */
    data class RankedHit(val entry: FileEntry, val score: Double, val nameHighlights: List<IntRange>)

    /**
     * Filters [entries] to the ones [parsed] matches and orders the result by [RANKING_ORDER] --
     * the rule `docs/product/apple-hig-study.md`'s search section states outright: a live query
     * ranks by best match, not by whatever the browser's own sort column says. Meant for
     * in-folder use, where every candidate is already in memory and a recursive walk would be
     * the wrong tool.
     */
    fun rank(
        entries: List<FileEntry>,
        parsed: ParsedQuery,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        tagsFor: (Uri) -> Set<String> = { emptySet() },
    ): List<RankedHit> {
        val ctx = EvalContext(nowMillis)
        val liveMatcher = matcher(zone, tagsFor)
        val terms = QueryCompiler.lexicalTerms(parsed.root)
        return entries
            .mapNotNull { entry -> liveMatcher.score(entry, toDoc(entry), parsed, ctx)?.let { entry to it } }
            .sortedWith(compareBy(RANKING_ORDER) { it.second })
            .map { (entry, scored) -> RankedHit(entry, scored.score, Normalizer.findMatches(entry.name, terms)) }
    }
}

/** A `content:` facet or a quoted phrase asked for the file's *contents*, not just its name --
 *  the recursive walk's cue to also open and read a candidate through its own bounded, text-only
 *  content pass. */
fun ParsedQuery.wantsContent(): Boolean =
    facets.any { it.key.equals("content", ignoreCase = true) } || containsPhrase(root)

private fun containsPhrase(node: QueryNode?): Boolean = when (node) {
    null -> false
    is QueryNode.Phrase -> true
    is QueryNode.And -> node.children.any(::containsPhrase)
    is QueryNode.Or -> node.children.any(::containsPhrase)
    is QueryNode.Not -> containsPhrase(node.child)
    else -> false
}

/** No constraint typed at all -- an unfiltered browse, the same reading [ParsedQuery.root] gives
 *  every other consumer in this module (`Matcher.matches`, `FacetEvaluator.matches`). */
fun ParsedQuery.isEmptyQuery(): Boolean = root == null
