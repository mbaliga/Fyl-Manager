package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [groupByDay]'s grouping, ordering, null-handling and singular/plural rules -- the properties the
 * frame-2 date-sectioned grid actually depends on. Runs under [RobolectricTestRunner] the same way
 * [io.github.mbaliga.fylz.browse.GroupedListingTest] does, purely so [FileEntry.uri] can hold a
 * real [Uri]; [groupByDay] itself touches no Android framework API (see its own KDoc).
 */
@RunWith(RobolectricTestRunner::class)
class DateSectionsTest {

    private val utc = ZoneOffset.UTC
    private val english = Locale.US

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(utc).toEpochMilli()

    private fun entry(name: String, modified: Long?) = FileEntry(
        uri = Uri.parse("content://test/${name.hashCode()}"),
        name = name,
        mimeType = "application/pdf",
        sizeBytes = 100L,
        lastModifiedMillis = modified,
        flags = 0,
        kind = EntryKind.PDF,
    )

    private fun labelFor(date: LocalDate): String =
        DateTimeFormatter.ofPattern("EEEE, MMMM d", english).format(date)

    private fun group(entries: List<FileEntry>, now: Long = at(2024, 9, 23)) =
        groupByDay(entries, now, zoneId = utc, locale = english)

    // ── grouping ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `entries on the same calendar day merge into one section, wherever they sit in the input`() {
        val entries = listOf(
            entry("a.pdf", at(2024, 9, 23, hour = 8)),
            entry("b.pdf", at(2024, 9, 22, hour = 10)),
            // Same day as "a.pdf" but not adjacent to it -- groupByDay groups by actual day value,
            // not by run-length over an assumed-sorted input.
            entry("c.pdf", at(2024, 9, 23, hour = 20)),
        )

        val sections = group(entries)

        assertEquals(2, sections.size)
        assertEquals(listOf("a.pdf", "c.pdf"), sections[0].entries.map { it.name })
        assertEquals(listOf("b.pdf"), sections[1].entries.map { it.name })
    }

    @Test
    fun `a section's label is the locale-aware weekday and date, computed from the same day the entries share`() {
        val day = LocalDate.of(2024, 9, 23)
        val sections = group(listOf(entry("a.pdf", at(2024, 9, 23))))

        assertEquals(labelFor(day), sections.single().label)
    }

    // ── ordering ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `dated sections sort newest day first`() {
        val entries = listOf(
            entry("old.pdf", at(2024, 1, 5)),
            entry("new.pdf", at(2024, 9, 23)),
            entry("mid.pdf", at(2024, 5, 1)),
        )

        val sections = group(entries)

        assertEquals(
            listOf(labelFor(LocalDate.of(2024, 9, 23)), labelFor(LocalDate.of(2024, 5, 1)), labelFor(LocalDate.of(2024, 1, 5))),
            sections.map { it.label },
        )
    }

    // ── null handling ────────────────────────────────────────────────────────────────────

    @Test
    fun `entries with no modified time land in one final Undated section, never a fabricated date`() {
        val entries = listOf(
            entry("dated.pdf", at(2024, 9, 23)),
            entry("no-date-1.pdf", null),
            entry("no-date-2.pdf", null),
        )

        val sections = group(entries)

        assertEquals(2, sections.size)
        assertEquals("Undated", sections.last().label)
        assertEquals(listOf("no-date-1.pdf", "no-date-2.pdf"), sections.last().entries.map { it.name })
        // Last regardless of how the dates would otherwise sort -- Undated is not a real day and
        // never competes with one for "newest".
        assertTrue(sections.dropLast(1).all { it.label != "Undated" })
    }

    @Test
    fun `an entirely undated listing is still just one section, not an empty dated list plus a stray one`() {
        val sections = group(listOf(entry("a.pdf", null), entry("b.pdf", null)))
        assertEquals(1, sections.size)
        assertEquals("Undated", sections.single().label)
    }

    @Test
    fun `no undated entries means no Undated section at all`() {
        val sections = group(listOf(entry("a.pdf", at(2024, 9, 23))))
        assertTrue(sections.none { it.label == "Undated" })
    }

    @Test
    fun `an empty listing groups to nothing`() {
        assertTrue(group(emptyList()).isEmpty())
    }

    // ── singular vs plural ───────────────────────────────────────────────────────────────

    @Test
    fun `a one-entry section reads singular, a multi-entry section reads plural`() {
        val entries = listOf(
            entry("solo.pdf", at(2024, 9, 23)),
            entry("a.pdf", at(2024, 5, 1)),
            entry("b.pdf", at(2024, 5, 1)),
            entry("c.pdf", at(2024, 5, 1)),
        )

        val sections = group(entries)

        assertEquals("1 document", sections.first { it.entries.size == 1 }.countLabel)
        assertEquals("3 documents", sections.first { it.entries.size == 3 }.countLabel)
    }

    // ── overriding the default English copy ─────────────────────────────────────────────

    @Test
    fun `undatedLabel and countLabel are overridable, for a caller wiring in localized resource strings`() {
        val sections = groupByDay(
            entries = listOf(entry("a.pdf", null)),
            nowMillis = at(2024, 9, 23),
            zoneId = utc,
            locale = english,
            undatedLabel = "Sin fecha",
            countLabel = { n -> "$n archivo(s)" },
        )

        assertEquals("Sin fecha", sections.single().label)
        assertEquals("1 archivo(s)", sections.single().countLabel)
    }
}
