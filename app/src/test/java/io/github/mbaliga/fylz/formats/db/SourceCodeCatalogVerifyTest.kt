package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.*
import org.junit.Test

class SourceCodeCatalogVerifyTest {
    private val specs = SourceCodeCatalog.specs

    @Test fun signatureSamplesSniffToSelfWithWrongExtension() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "file.zzzwrong").id)
    }

    @Test fun verilogPresent() {
        val sn = FormatSniffer(specs)
        val v = sn.forExtension("v")
        assertTrue("no spec claims .v (Verilog dropped by distinctBy id)", v.isNotEmpty())
        assertEquals("Verilog HDL", v.first().label.removeSuffix(" source").let { if (it.contains("Verilog")) "Verilog HDL" else it })
    }

    @Test fun noRealIdsDroppedSilently() {
        // every extension string literal of an entry shadowed by distinctBy would be lost; count raw vs distinct is not visible,
        // so assert the known shadow pair
        assertNotNull(specs.firstOrNull { "vh" in it.extensions })
    }

    @Test fun idIsPrimaryExtensionOrWholeName() {
        val bad = specs.filter { s -> s.extensions.none { e -> s.id == e || s.id.startsWith("$e-") } }.map { it.id + "->" + it.extensions.first() }
        assertTrue("ids not among their own extensions: $bad", bad.isEmpty())
    }

    @Test fun binaryFormatsNotFlaggedText() {
        for (id in listOf("fasl", "mlx")) assertFalse("$id is binary/zip", specs.first { it.id == id }.text)
    }

    @Test fun noGenericMimeSteals() {
        val generic = setOf("application/json", "application/xml", "application/rdf+xml", "text/plain")
        val bad = specs.filter { s -> s.mimeTypes.any { it in generic } }.map { it.id }
        assertTrue("generic mime claimed: $bad", bad.isEmpty())
    }

    @Test fun pythonSourceNotCompiledMime() {
        assertFalse(specs.first { it.id == "py" }.mimeTypes.contains("application/x-python-code"))
    }

    @Test fun cmakeListsTxtResolves() {
        val sn = FormatSniffer(specs)
        assertEquals("cmakelists", sn.sniff("project(x)".toByteArray(), "CMakeLists.txt").id)
    }

    @Test fun allExtensionsNonTrivialCasing() {
        for (s in specs) for (e in s.extensions) assertEquals(e, e.trim())
    }
}
