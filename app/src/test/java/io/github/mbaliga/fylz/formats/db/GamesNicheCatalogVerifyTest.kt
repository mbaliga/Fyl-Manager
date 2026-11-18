package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class GamesNicheCatalogVerifyTest {
    private val specs = GamesNicheCatalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun sampleSniffsToOwnSpecWithWrongExtension() {
        val bad = mutableListOf<String>()
        for (s in specs) for (g in s.signatures) {
            val r = sniffer.sniff(g.sample() + ByteArray(64), "file.zzqq")
            if (r.spec?.id != s.id) bad += "${s.id}->${r.spec?.id}"
        }
        assertTrue("wrong-extension misidentification: $bad", bad.isEmpty())
    }

    @Test fun noTwoSpecsShareIdenticalSignature() {
        val seen = HashMap<String, String>(); val bad = mutableListOf<String>()
        for (s in specs) for (g in s.signatures) {
            val k = g.sample().joinToString("") { "%02x".format(it) } + "@" + g.end
            val o = seen.put(k, s.id)
            if (o != null && o != s.id) bad += "$o/${s.id}"
        }
        assertTrue("shared signatures: $bad", bad.isEmpty())
    }

    @Test fun idIsAPrimaryExtensionOrListedExtension() {
        val bad = specs.filter { it.id !in it.extensions }.map { it.id }
        assertTrue("id not among extensions: $bad", bad.isEmpty())
    }

    @Test fun idsAndExtensionsLowercase() {
        for (s in specs) {
            assertEquals(s.id, s.id.lowercase())
            for (e in s.extensions) assertEquals("${s.id}:$e", e.lowercase(), e)
        }
    }

    @Test fun specialisationsResolveViaContainer() {
        for (s in specs) {
            val c = s.containerId ?: continue
            val cs = specs.first { it.id == c }
            if (cs.signatures.isEmpty()) continue
            val r = sniffer.sniff(cs.signatures.first().sample() + ByteArray(64), "a.${s.extensions.first()}")
            assertEquals(s.id, r.spec?.id)
        }
    }

    @Test fun noReservedGenericIdsClashWithMarkdown() {
        assertTrue("id md is Markdown elsewhere", specs.none { it.id == "md" })
    }
}
