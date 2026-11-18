package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.*
import org.junit.Assert.*
import org.junit.Test

class Gap1x4CatalogVerifyTest {
    private val specs = Gap1x4Catalog.specs
    @Test fun wrongExtensionStillSniffsBySignature() {
        val sn = FormatSniffer(specs)
        for (s in specs) for (g in s.signatures)
            assertEquals(s.id, sn.sniff(g.sample(), "file.bin").id)
    }
    @Test fun idIsPrimaryExtension() = specs.forEach { assertEquals(it.id, it.extensions.first()) }
    @Test fun extensionsLowercaseNoDots() = specs.forEach { s ->
        s.extensions.forEach { assertTrue(s.id + ":" + it, it == it.lowercase() && !it.startsWith(".") && it.isNotBlank()) } }
    @Test fun noExtensionShared() {
        val m = specs.flatMap { s -> s.extensions.map { it to s.id } }.groupBy({ it.first }, { it.second })
        m.forEach { (e, ids) -> assertEquals(e, 1, ids.toSet().size) }
    }
    @Test fun mimeLooksValid() = specs.forEach { s -> s.mimeTypes.forEach { assertTrue(it, Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(it)) } }
    @Test fun descriptionsNotBlank() = specs.forEach { assertTrue(it.id, it.description.isNotBlank()) }
    @Test fun sigsAtLeastFourBytes() = specs.forEach { s -> s.signatures.forEach { assertTrue(s.id, it.parts.sumOf { p -> p.bytes.size } >= 4) } }
    @Test fun extensionLookupWorks() { val sn = FormatSniffer(specs); for (s in specs) for (e in s.extensions) assertTrue(e, sn.forExtension(e).any { it.id == s.id }) }
}
