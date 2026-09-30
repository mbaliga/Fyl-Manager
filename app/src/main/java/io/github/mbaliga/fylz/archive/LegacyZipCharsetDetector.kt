package io.github.mbaliga.fylz.archive

import java.nio.charset.Charset

/**
 * A legacy single- or double-byte code page a pre-UTF-8 ZIP tool may have used for a filename
 * (M3.7, `docs/agent/REVIEW_QUEUE.md`'s M3.7 entry): the five the brief names, covering the
 * common legacy ZIP producers -- DOS/Windows CP-437 in the West, CP-866 for Cyrillic DOS, and the
 * three East Asian double-byte pages. [AUTO] is not a real charset at all: it means "run
 * [LegacyZipCharsetDetector.detect] for this entry", so the header-bar override control can offer
 * it as a sixth choice (the default) without a decoder needing a [Charset] for it.
 *
 * Every named entry's [charsetName] is a standard JDK charset name and needs no new dependency;
 * `LegacyZipCharsetSmokeTest` asserts each one actually resolves via [Charset.forName] on this
 * JDK, since a typo here would otherwise fail only the first time a lossy name of that family was
 * ever opened, on a real device, far from where the typo was made.
 */
enum class ArchiveNameEncoding(val label: String, val charsetName: String?) {
    AUTO("Auto-detect", null),
    CP437("CP-437 (Western Europe / US)", "Cp437"),
    CP866("CP-866 (Cyrillic)", "Cp866"),
    GBK("GBK (Chinese)", "GBK"),
    SHIFT_JIS("Shift-JIS (Japanese)", "Shift_JIS"),
    EUC_KR("EUC-KR (Korean)", "EUC-KR"),
    ;

    /** `null` for [AUTO]; otherwise the resolved [Charset], resolved once per entry. */
    val charset: Charset? by lazy { charsetName?.let(Charset::forName) }
}

/**
 * A small heuristic for [ArchiveNameEncoding.AUTO] (M3.7): which of the four non-CP437 legacy
 * charsets a lossy name's raw bytes most look like, falling back to [ArchiveNameEncoding.CP437]
 * (by far the most common legacy ZIP charset for Western names) or, when a `0x90`-`0x9F` byte
 * hints at Cyrillic uppercase, [ArchiveNameEncoding.CP866].
 *
 * **This is deliberately not exhaustive** -- state its limits, per the brief, rather than
 * over-engineer a perfect detector for a display-only, always-overridable feature:
 *
 * - CP-437 and CP-866 share almost their entire high byte range (both put accented Latin /
 *   Cyrillic letters across `0x80`-`0xAF` and `0xE0`-`0xEF`), so there is **no** reliable
 *   byte-range signal that tells them apart in general. This detector only recognises `0x90`-
 *   `0x9F` (CP-866's own "second half" of Cyrillic uppercase, a block CP-437 barely uses for
 *   anything but a few currency symbols) as a CP-866 hint; a Cyrillic name that happens to use
 *   none of those 16 codepoints, or a Western name that does use one of the rare CP-437 symbols
 *   there, guesses wrong. The manual override exists precisely for this case.
 * - A real multi-character Cyrillic (CP-866) name is often misdetected as Shift-JIS or GBK
 *   instead of falling through to the CP-866 check at all, because CP-866's own high bytes
 *   (`0xE0`-`0xEF` lowercase, in particular) sit squarely inside those pages' own lead/trail
 *   byte ranges, and a real word supplies several such bytes in a row. East Asian double-byte
 *   evidence is checked first because it is otherwise the *more* reliable signal (a real Shift-
 *   JIS/GBK/EUC-KR name is normally many bytes long and entirely double-byte, so a false match
 *   against a short single-byte name is rare) -- but it means CP-866 in practice is usually
 *   reached only through the manual override, not auto-detected.
 * - GBK's own lead/trail ranges are a superset of EUC-KR's, so EUC-KR is checked first; Shift-
 *   JIS's ranges overlap both, so it is checked first of all three. Even so, a name that is
 *   genuinely ambiguous between two of the three East Asian pages may guess either one.
 */
object LegacyZipCharsetDetector {
    fun detect(rawPathBytes: ByteArray): ArchiveNameEncoding {
        if (hasDoubleByteRun(rawPathBytes, SHIFT_JIS_LEAD, SHIFT_JIS_TRAIL, SHIFT_JIS_LEAD_HIGH)) return ArchiveNameEncoding.SHIFT_JIS
        if (hasDoubleByteRun(rawPathBytes, EUC_KR_LEAD, EUC_KR_TRAIL)) return ArchiveNameEncoding.EUC_KR
        if (hasDoubleByteRun(rawPathBytes, GBK_LEAD, GBK_TRAIL)) return ArchiveNameEncoding.GBK
        val highBytes = rawPathBytes.map { it.toInt() and 0xFF }.filter { it >= 0x80 }
        return if (highBytes.any { it in CP866_SIGNAL }) ArchiveNameEncoding.CP866 else ArchiveNameEncoding.CP437
    }

    /** Decodes [rawPathBytes] under [encoding], resolving [ArchiveNameEncoding.AUTO] via [detect] first. */
    fun decode(rawPathBytes: ByteArray, encoding: ArchiveNameEncoding): String {
        val resolved = if (encoding == ArchiveNameEncoding.AUTO) detect(rawPathBytes) else encoding
        val charset = resolved.charset ?: return String(rawPathBytes, Charsets.UTF_8)
        return String(rawPathBytes, charset)
    }

    /** Whether any adjacent pair of bytes falls in [leadRange] then [trailRange] -- the structural
     * shape of a real double-byte character, which a single-byte code page's own bytes only rarely
     * produce by chance for a run of more than one character. */
    private fun hasDoubleByteRun(bytes: ByteArray, leadRange: IntRange, trailRange: IntRange): Boolean {
        for (i in 0 until bytes.size - 1) {
            val lead = bytes[i].toInt() and 0xFF
            val trail = bytes[i + 1].toInt() and 0xFF
            if (lead in leadRange && trail in trailRange) return true
        }
        return false
    }

    // Simplified lead/trail ranges (a real decoder's own tables are pickier about a handful of
    // reserved codepoints inside these); good enough for a structural "looks double-byte" guess.
    private val SHIFT_JIS_LEAD = 0x81..0x9F
    private val SHIFT_JIS_LEAD_HIGH = 0xE0..0xFC
    private val SHIFT_JIS_TRAIL = 0x40..0xFC
    private val GBK_LEAD = 0x81..0xFE
    private val GBK_TRAIL = 0x40..0xFE
    private val EUC_KR_LEAD = 0xA1..0xFE
    private val EUC_KR_TRAIL = 0xA1..0xFE
    private val CP866_SIGNAL = 0x90..0x9F

    private fun hasDoubleByteRun(bytes: ByteArray, lead: IntRange, trail: IntRange, secondLead: IntRange): Boolean =
        hasDoubleByteRun(bytes, lead, trail) || hasDoubleByteRun(bytes, secondLead, trail)
}
