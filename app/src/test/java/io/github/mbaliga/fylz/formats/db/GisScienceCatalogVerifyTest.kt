package io.github.mbaliga.fylz.formats.db

import io.github.mbaliga.fylz.formats.FormatSniffer
import org.junit.Assert.*
import org.junit.Test

class GisScienceCatalogVerifyTest {
    private val specs = GisScienceCatalog.specs
    private val sniffer = FormatSniffer(specs)

    @Test fun sampleSniffsToSelfWithWrongExtension() {
        val bad = mutableListOf<String>()
        for (s in specs) for (g in s.signatures) {
            // specs whose signature set also matches the sample are legitimately ambiguous
            val sample = g.sample()
            val twins = specs.filter { o -> o.signatures.any { it.matches(sample) && it.specificity == g.specificity } }.map { it.id }
            val r = sniffer.sniff(sample, "file.wrongext")
            val got = r.spec?.id
            if (got !in twins) bad += "${s.id}->$got"
            if (got != null && got !in twins) continue
            if (s.id !in (twins)) bad += "${s.id} not in twins"
        }
        assertTrue("wrong-ext misidentifications: $bad", bad.isEmpty())
    }

    @Test fun signatureLengthAtLeastFourConstrainedBytes() {
        val weak = specs.flatMap { s -> s.signatures.filter { it.specificity < 4 }.map { s.id } }
        assertTrue("weak signatures: $weak", weak.isEmpty())
    }

    @Test fun noDuplicateMimesOrExtensionsInsideCatalogExceptKnown() {
        val ext = specs.flatMap { s -> s.extensions.map { it to s.id } }.groupBy({ it.first }, { it.second })
            .filter { it.value.size > 1 }
        val allowed = setOf("las") // las (LiDAR) vs las-well, resolved by header
        assertTrue("extension clashes: ${ext.filterKeys { it !in allowed }}", ext.keys.all { it in allowed })
    }

    @Test fun mimesLowercase() {
        assertTrue(specs.all { s -> s.mimeTypes.all { it == it.lowercase() } })
    }

    @Test fun zipAndGzipSpecialisationsDeclareContainer() {
        val zips = listOf("kmz", "qgz", "lpk", "mph", "tpk", "tpkx", "mpk", "ppkx") // npz lives in DataConfigCatalog
        val missing = zips.filter { id -> specs.first { it.id == id }.containerId != "zip" }
        assertTrue("zip specialisations without containerId=zip: $missing", missing.isEmpty())
        val sq = listOf("mbtiles").filter { id -> specs.first { it.id == id }.containerId != "sqlite" }
        assertTrue("sqlite specialisations without containerId: $sq", sq.isEmpty())
    }

    /** Ids that already exist in OTHER published catalogs (other db catalogs) for a DIFFERENT or duplicate spec. */
    @Test fun idsDoNotCollideWithOtherCatalogs() {
        val elsewhere = setOf(
            "bdf", "e57", "ecw", "gml", "gro", "hl7", "las", "laz", "mat", "mid", "mif", "nc", "npy", "npz",
            "odb", "pdb", "psf", "rdata", "rds", "sam", "scf", "sdf", "sid", "smi", "spc", "sps", "spv",
            "vcf", "vtk", "vtu", "xyz", "h5", "hdf",
        )
        val clash = specs.map { it.id }.filter { it in elsewhere }
        assertTrue("ids already used by other catalogs: $clash", clash.isEmpty())
    }
}
