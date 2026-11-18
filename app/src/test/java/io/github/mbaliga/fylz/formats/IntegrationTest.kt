package io.github.mbaliga.fylz.formats

import io.github.mbaliga.fylz.formats.inspect.InspectorRegistry
import org.junit.Assert.*
import org.junit.Test

class IntegrationTest {
    private val specs = FormatCatalog.all

    @Test fun idsGloballyUnique() {
        val dup = specs.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids $dup", dup.isEmpty())
    }

    private fun sigKey(s: Signature) = s.parts.joinToString("+") {
        "${it.offset}:" + it.bytes.joinToString("") { b -> "%02x".format(b) } + ":" + (it.mask?.joinToString("") { b -> "%02x".format(b) } ?: "")
    }

    @Test fun sharedExtensions() {
        val byExt = HashMap<String, MutableList<FormatSpec>>()
        for (s in specs) for (e in s.extensions) byExt.getOrPut(e) { mutableListOf() }.add(s)
        val shared = byExt.filter { it.value.size > 1 }
        println("extensions claimed by >1 spec: ${shared.size}")
        for ((e, l) in shared.toSortedMap()) println("  $e -> ${l.map { it.id }}")
        val bad = mutableListOf<String>()
        for ((e, l) in shared) {
            for (i in l.indices) for (j in i + 1 until l.size) {
                val a = l[i].signatures.map(::sigKey).toSet(); val b = l[j].signatures.map(::sigKey).toSet()
                if (a.intersect(b).isNotEmpty()) bad += "$e: ${l[i].id} / ${l[j].id}"
            }
        }
        assertTrue("identical signatures on shared extension: $bad", bad.isEmpty())
    }

    @Test fun signaturesSniffToOwnSpec() {
        val sn = FormatCatalog.sniffer
        val fails = mutableListOf<String>()
        val benign = mutableListOf<String>()
        for (spec in specs) for (sg in spec.signatures) {
            val name = "file." + (spec.extensions.firstOrNull() ?: spec.id)
            val r = sn.sniff(sg.sample(), name)
            val got = r.spec
            if (got == null) { fails += "${spec.id}: no match"; continue }
            if (got.id == spec.id) continue
            val gs = got.signatures.filter { it.matches(sg.sample()) }.maxOfOrNull { it.specificity } ?: -1
            if (gs > sg.specificity) benign += "${spec.id} -> ${got.id} ($gs > ${sg.specificity})"
            else fails += "${spec.id} -> ${got.id} (${sg.specificity} vs $gs)"
        }
        println("more-specific collisions: ${benign.size}"); benign.forEach { println("  $it") }
        assertTrue("sniff collisions (${fails.size}):\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun inspectorIdsExistInCatalog() {
        val ids = specs.map { it.id }.toSet()
        val missing = InspectorRegistry.all.flatMap { i -> i.formatIds.filter { it !in ids }.map { it to i.javaClass.simpleName } }
        assertTrue("inspector ids missing from catalog: $missing", missing.isEmpty())
    }

    @Test fun sniffingIsDeterministic() {
        val a = FormatSniffer(specs); val b = FormatSniffer(specs)
        for (spec in specs) for (sg in spec.signatures) {
            val h = sg.sample(); val n = "x." + (spec.extensions.firstOrNull() ?: spec.id)
            assertEquals(a.sniff(h, n).id, b.sniff(h, n).id)
            assertEquals(a.sniff(h, n).id, a.sniff(h, n).id)
        }
    }
}
