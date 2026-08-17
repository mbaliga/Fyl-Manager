package io.github.mbaliga.fylz.search

import android.net.Uri
import dev.aarso.search.ChipKind
import dev.aarso.search.Diagnostic
import dev.aarso.search.EvalContext
import dev.aarso.search.Op
import dev.aarso.search.QueryCompiler
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/**
 * `FylzSearch` replaced the flat `ext:`/`type:`/`size:`/`content:` grammar this suite used to
 * exercise directly against the now-deleted `SearchQuery`. These cover the same intents through
 * the search-core adapter: the facet vocabulary in isolation, the natural-language pre-pass, and
 * the ranking it unlocks. The recursive walk itself still needs a real `ContentResolver` and
 * stays exercised on device.
 */
@RunWith(RobolectricTestRunner::class)
class FylzSearchTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    // A Wednesday, so "this week" starts two days back and "last week" is the full Monday..Monday
    // before that -- wide enough that "3 days ago" and "10 days ago" land on opposite sides of it.
    private val now: Long = Instant.parse("2026-08-12T12:00:00Z").toEpochMilli()
    private val ctx = EvalContext(now)
    private val registry = FylzSearch.registry(zone)

    private fun entry(
        name: String,
        kind: EntryKind = EntryKind.TEXT,
        size: Long? = 1_000,
        mime: String = "text/plain",
        modifiedMillis: Long? = now,
    ) = FileEntry(
        uri = Uri.parse("content://test/$name"),
        name = name,
        mimeType = mime,
        sizeBytes = size,
        lastModifiedMillis = modifiedMillis,
        flags = 0,
        kind = kind,
    )

    private fun daysAgo(days: Long): Long = now - days * 86_400_000L

    // ---- ext ------------------------------------------------------------------------------

    @Test
    fun `ext filter accepts a comma separated list and tolerates leading dots`() {
        val field = registry["ext"]!!
        assertTrue(field.matches(entry("a.pdf"), Op.EQ, "pdf,png", ctx))
        assertTrue(field.matches(entry("b.PNG"), Op.EQ, ".pdf,PNG", ctx))
        assertFalse(field.matches(entry("c.txt"), Op.EQ, "pdf,png", ctx))
    }

    @Test
    fun `ext has an extension alias resolving to the same field`() {
        assertTrue(registry.recognizes("extension"))
        assertEquals(registry["ext"], registry["extension"])
    }

    @Test
    fun `ext NE inverts the match`() {
        val field = registry["ext"]!!
        assertFalse(field.matches(entry("a.pdf"), Op.NE, "pdf", ctx))
        assertTrue(field.matches(entry("a.pdf"), Op.NE, "png", ctx))
    }

    // ---- type -----------------------------------------------------------------------------

    @Test
    fun `type filter maps friendly aliases onto EntryKind`() {
        val field = registry["type"]!!
        assertTrue(field.matches(entry("x", kind = EntryKind.IMAGE), Op.EQ, "photo", ctx))
        assertTrue(field.matches(entry("x", kind = EntryKind.DIRECTORY), Op.EQ, "folder", ctx))
        assertTrue(field.matches(entry("x", kind = EntryKind.ARCHIVE), Op.EQ, "zip", ctx))
    }

    @Test
    fun `type has a kind alias resolving to the same field`() {
        assertEquals(registry["type"], registry["kind"])
    }

    @Test
    fun `an unknown type value is unbacked, not silently dropped`() {
        val field = registry["type"]!!
        assertFalse(field.isBacked("widget"))
        val parsed = FylzSearch.parse("type:widget", now, zone)
        assertTrue(parsed.diagnostics.any { it is Diagnostic.UnindexedFacet && it.key == "type" })
    }

    @Test
    fun `document groups text, markdown and pdf`() {
        val field = registry["type"]!!
        assertTrue(field.matches(entry("x", kind = EntryKind.TEXT), Op.EQ, "document", ctx))
        assertTrue(field.matches(entry("x", kind = EntryKind.MARKDOWN), Op.EQ, "document", ctx))
        assertTrue(field.matches(entry("x", kind = EntryKind.PDF), Op.EQ, "document", ctx))
        assertFalse(field.matches(entry("x", kind = EntryKind.IMAGE), Op.EQ, "document", ctx))
    }

    @Test
    fun `screenshot is an image whose name says so`() {
        val field = registry["type"]!!
        assertTrue(field.matches(entry("Screenshot_2026.png", kind = EntryKind.IMAGE), Op.EQ, "screenshot", ctx))
        assertFalse(field.matches(entry("vacation.png", kind = EntryKind.IMAGE), Op.EQ, "screenshot", ctx))
        assertFalse(field.matches(entry("screenshot.pdf", kind = EntryKind.PDF), Op.EQ, "screenshot", ctx))
    }

    // ---- size -----------------------------------------------------------------------------

    @Test
    fun `size bounds parse 1024-based units and apply the right direction`() {
        val field = registry["size"]!!
        assertTrue(field.matches(entry("x", size = 20L * 1_024 * 1_024), Op.GT, "10mb", ctx))
        assertFalse(field.matches(entry("x", size = 5L * 1_024 * 1_024), Op.GT, "10mb", ctx))
        assertTrue(field.matches(entry("x", size = 1_000), Op.LT, "500kb", ctx))
        assertFalse(field.matches(entry("x", size = 900_000), Op.LT, "500kb", ctx))
    }

    @Test
    fun `size accepts kib mib gib as the same 1024-based units`() {
        val field = registry["size"]!!
        assertTrue(field.matches(entry("x", size = 2L * 1_024 * 1_024), Op.GT, "1mib", ctx))
        assertTrue(field.matches(entry("x", size = 5L * 1_024), Op.EQ, "5kb", ctx))
        assertTrue(field.matches(entry("x", size = 5L * 1_024), Op.EQ, "5kib", ctx))
    }

    @Test
    fun `an entry with unknown size cannot satisfy a size bound`() {
        assertFalse(registry["size"]!!.matches(entry("x", size = null), Op.GT, "1kb", ctx))
    }

    @Test
    fun `size range is backed and both bounds apply`() {
        val field = registry["size"]!!
        assertTrue(field.isBacked("10mb..50mb"))
        val parsed = FylzSearch.parse("size:10mb..50mb", now, zone)
        assertTrue(parsed.diagnostics.none { it is Diagnostic.UnindexedFacet })
        val inRange = entry("x", size = 30L * 1_024 * 1_024)
        val below = entry("x", size = 1L * 1_024 * 1_024)
        val matcher = FylzSearch.matcher(zone)
        assertTrue(matcher.matches(inRange, FylzSearch.toDoc(inRange), parsed, ctx))
        assertFalse(matcher.matches(below, FylzSearch.toDoc(below), parsed, ctx))
    }

    // ---- modified -------------------------------------------------------------------------

    @Test
    fun `modified today and yesterday resolve relative to the given clock`() {
        val field = registry["modified"]!!
        assertTrue(field.matches(entry("x", modifiedMillis = now), Op.EQ, "today", ctx))
        assertTrue(field.matches(entry("x", modifiedMillis = daysAgo(1)), Op.EQ, "yesterday", ctx))
        assertFalse(field.matches(entry("x", modifiedMillis = daysAgo(1)), Op.EQ, "today", ctx))
    }

    @Test
    fun `modified has date and when aliases resolving to the same field`() {
        assertEquals(registry["modified"], registry["date"])
        assertEquals(registry["modified"], registry["when"])
    }

    @Test
    fun `modified GTE is a since-style lower bound`() {
        val field = registry["modified"]!!
        assertTrue(field.matches(entry("x", modifiedMillis = daysAgo(3)), Op.GTE, "last-week", ctx))
        assertFalse(field.matches(entry("x", modifiedMillis = daysAgo(10)), Op.GTE, "last-week", ctx))
    }

    @Test
    fun `modified LT is a before-style upper bound`() {
        val field = registry["modified"]!!
        assertTrue(field.matches(entry("x", modifiedMillis = daysAgo(10)), Op.LT, "last-week", ctx))
        assertFalse(field.matches(entry("x", modifiedMillis = daysAgo(3)), Op.LT, "last-week", ctx))
    }

    @Test
    fun `an entry with no modified time cannot satisfy a date facet`() {
        assertFalse(registry["modified"]!!.matches(entry("x", modifiedMillis = null), Op.EQ, "today", ctx))
    }

    // ---- name -------------------------------------------------------------------------------

    @Test
    fun `name is a case-insensitive substring`() {
        val field = registry["name"]!!
        assertTrue(field.matches(entry("Quarterly Report.txt"), Op.EQ, "report", ctx))
        assertFalse(field.matches(entry("Quarterly Report.txt"), Op.EQ, "invoice", ctx))
    }

    // ---- tag ------------------------------------------------------------------------------

    @Test
    fun `tag matches against the injected tag lookup`() {
        val uri = Uri.parse("content://test/tagged")
        val tagged = FileEntry(uri, "tagged", "text/plain", 100, now, 0, EntryKind.TEXT)
        val tagsFor: (Uri) -> Set<String> = { if (it == uri) setOf("work", "urgent") else emptySet() }
        val field = FylzSearch.registry(zone, tagsFor)["tag"]!!
        assertTrue(field.matches(tagged, Op.EQ, "urgent", ctx))
        assertFalse(field.matches(tagged, Op.EQ, "personal", ctx))
    }

    // ---- content --------------------------------------------------------------------------

    @Test
    fun `content is a marker, not a filter -- every subject passes it`() {
        assertTrue(registry["content"]!!.matches(entry("anything"), Op.EQ, "", ctx))
    }

    @Test
    fun `content marker alone is still inert without a term to search for`() {
        val withTerm = FylzSearch.parse("content: budget", now, zone)
        assertTrue(withTerm.wantsContent())
        assertTrue(QueryCompiler.lexicalTerms(withTerm.root).isNotEmpty())

        // The recursive engine gates its content pass on wantsContent() AND a non-empty term
        // list; a bare "content:" satisfies only the first, so it stays inert end to end.
        val bare = FylzSearch.parse("content:", now, zone)
        assertTrue(bare.wantsContent())
        assertTrue(QueryCompiler.lexicalTerms(bare.root).isEmpty())
    }

    @Test
    fun `a quoted phrase also opts into content search`() {
        assertTrue(FylzSearch.parse("\"deployment plan\"", now, zone).wantsContent())
    }

    // ---- is -------------------------------------------------------------------------------

    @Test
    fun `is distinguishes folders from files`() {
        val field = registry["is"]!!
        assertTrue(field.matches(entry("x", kind = EntryKind.DIRECTORY), Op.EQ, "folder", ctx))
        assertFalse(field.matches(entry("x", kind = EntryKind.DIRECTORY), Op.EQ, "file", ctx))
        assertTrue(field.matches(entry("x", kind = EntryKind.TEXT), Op.EQ, "file", ctx))
    }

    // ---- migrated grammar intents -----------------------------------------------------------

    @Test
    fun `blank query is empty`() {
        assertTrue(FylzSearch.parse("   ", now, zone).isEmptyQuery())
    }

    @Test
    fun `free text terms all have to appear in the name`() {
        val parsed = FylzSearch.parse("report final", now, zone)
        val matcher = FylzSearch.matcher(zone)
        val hit = entry("final-report.pdf")
        val miss = entry("report.pdf")
        assertTrue(matcher.matches(hit, FylzSearch.toDoc(hit), parsed, ctx))
        assertFalse(matcher.matches(miss, FylzSearch.toDoc(miss), parsed, ctx))
    }

    @Test
    fun `operators combine with free text`() {
        val parsed = FylzSearch.parse("invoice ext:pdf size:>1mb", now, zone)
        val matcher = FylzSearch.matcher(zone)
        val hit = entry("2026-invoice-final.pdf", size = 3L * 1_024 * 1_024)
        val miss = entry("2026-invoice-final.pdf", size = 1_000)
        assertTrue(matcher.matches(hit, FylzSearch.toDoc(hit), parsed, ctx))
        assertFalse(matcher.matches(miss, FylzSearch.toDoc(miss), parsed, ctx))
    }

    // ---- natural language ---------------------------------------------------------------------

    @Test
    fun `photos from last week matches an image modified 3 days ago, not 10`() {
        val parsed = FylzSearch.parse("photos from last week", now, zone)
        assertTrue(parsed.chips.any { it.key == "type" })
        assertTrue(parsed.chips.any { it.key == "modified" })

        val recent = entry("beach.jpg", kind = EntryKind.IMAGE, modifiedMillis = daysAgo(3))
        val stale = entry("party.jpg", kind = EntryKind.IMAGE, modifiedMillis = daysAgo(10))
        val ranked = FylzSearch.rank(listOf(recent, stale), parsed, now, zone).map { it.entry.name }

        assertEquals(listOf("beach.jpg"), ranked)
    }

    @Test
    fun `large videos combines a size word and a kind noun`() {
        val parsed = FylzSearch.parse("large videos", now, zone)
        val matcher = FylzSearch.matcher(zone)
        val hit = entry("movie.mp4", kind = EntryKind.VIDEO, size = 40L * 1_024 * 1_024)
        val miss = entry("clip.mp4", kind = EntryKind.VIDEO, size = 1L * 1_024 * 1_024)
        assertTrue(matcher.matches(hit, FylzSearch.toDoc(hit), parsed, ctx))
        assertFalse(matcher.matches(miss, FylzSearch.toDoc(miss), parsed, ctx))
    }

    @Test
    fun `pdfs I downloaded yesterday leaves the unrecognized verb as a term`() {
        val parsed = FylzSearch.parse("pdfs I downloaded yesterday", now, zone)
        assertTrue(parsed.chips.any { it.key == "type" && it.text.contains("pdf") })
        assertTrue(parsed.chips.any { it.key == "modified" })
        assertTrue(parsed.chips.any { it.kind == ChipKind.TERM && it.text.equals("downloaded", ignoreCase = true) })
    }

    @Test
    fun `report 2024 becomes a year facet plus a bare term`() {
        val parsed = FylzSearch.parse("report 2024", now, zone)
        assertTrue(parsed.facets.any { it.key == "modified" && it.value == "2024" })
        assertTrue(parsed.chips.any { it.kind == ChipKind.TERM && it.text.equals("report", ignoreCase = true) })
    }

    @Test
    fun `structured input passes through untouched alongside natural language`() {
        val parsed = FylzSearch.parse("ext:pdf photos from last week", now, zone)
        assertTrue(parsed.facets.any { it.key == "ext" && it.value == "pdf" })
        assertTrue(parsed.facets.any { it.key == "type" && it.value == "image" })
        assertTrue(parsed.facets.any { it.key == "modified" })
    }

    @Test
    fun `empty and garbage input degrades to an inert query, never throws`() {
        assertTrue(FylzSearch.parse("", now, zone).isEmptyQuery())
        val garbage = FylzSearch.parse(":::: \"unterminated", now, zone)
        assertFalse(garbage.isEmptyQuery())
    }

    // ---- ranking ------------------------------------------------------------------------------

    @Test
    fun `a name hit outranks a path-only hit`() {
        val parsed = FylzSearch.parse("invoice", now, zone)
        val matcher = FylzSearch.matcher(zone)

        val nameHit = entry("invoice-2026.pdf", kind = EntryKind.PDF)
        val nameScore = matcher.score(nameHit, FylzSearch.toDoc(nameHit), parsed, ctx)!!.score

        val pathOnly = entry("summary.pdf", kind = EntryKind.PDF)
        val pathScore = matcher.score(pathOnly, FylzSearch.toDoc(pathOnly, path = "invoice-archive"), parsed, ctx)!!.score

        assertTrue(nameScore > pathScore)
    }

    @Test
    fun `on a tied score, the newer entry ranks first`() {
        // type:pdf alone has no lexical term to score, so both matches are genuinely tied at
        // 0.0 and RANKING_ORDER's timestamp tiebreak is what actually orders them.
        val parsed = FylzSearch.parse("type:pdf", now, zone)
        val newer = entry("a.pdf", kind = EntryKind.PDF, modifiedMillis = now)
        val older = entry("b.pdf", kind = EntryKind.PDF, modifiedMillis = daysAgo(30))

        val ranked = FylzSearch.rank(listOf(older, newer), parsed, now, zone)

        assertEquals(listOf(0.0, 0.0), ranked.map { it.score })
        assertEquals(newer.uri, ranked.first().entry.uri)
    }

    // ---- highlights -----------------------------------------------------------------------

    @Test
    fun `highlight coordinates land on a mixed-case name`() {
        val parsed = FylzSearch.parse("report", now, zone)
        val mixed = entry("Quarterly-REPORT-Final.pdf", kind = EntryKind.PDF)

        val hit = FylzSearch.rank(listOf(mixed), parsed, now, zone).single()

        assertEquals("REPORT", mixed.name.substring(10, 16))
        assertEquals(listOf(10..15), hit.nameHighlights)
    }
}
