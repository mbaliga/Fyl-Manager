package io.github.mbaliga.fylz.ui.places

import io.github.mbaliga.fylz.search.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandingFactsTest {

    @Test fun classifiesByMimeAndExtension() {
        assertEquals(StorageCategory.PHOTOS, StorageClassifier.classify("image/jpeg", "a.jpg"))
        assertEquals(StorageCategory.VIDEOS, StorageClassifier.classify("video/mp4", "a.mp4"))
        assertEquals(StorageCategory.AUDIO, StorageClassifier.classify("audio/mpeg", "a.mp3"))
        assertEquals(StorageCategory.DOCUMENTS, StorageClassifier.classify("application/pdf", "a.pdf"))
        assertEquals(StorageCategory.DOCUMENTS, StorageClassifier.classify("text/plain", "a.txt"))
        assertEquals(
            StorageCategory.DOCUMENTS,
            StorageClassifier.classify("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a.docx"),
        )
        // MediaStore often reports no MIME type at all, so the extension has to carry it.
        assertEquals(StorageCategory.DOCUMENTS, StorageClassifier.classify(null, "notes.MD"))
    }

    @Test fun apksAreNotDocumentsOrArchives() {
        assertEquals(StorageCategory.APKS, StorageClassifier.classify("application/vnd.android.package-archive", "x.apk"))
        // A .apk served as a zip is still an APK.
        assertEquals(StorageCategory.APKS, StorageClassifier.classify("application/zip", "bundle.apks"))
        assertEquals(StorageCategory.APKS, StorageClassifier.classify(null, "Thing.XAPK"))
    }

    @Test fun unknownFilesAreNotCounted() {
        assertNull(StorageClassifier.classify("application/octet-stream", "blob.bin"))
        assertNull(StorageClassifier.classify(null, null))
        assertNull(StorageClassifier.classify("application/zip", "a.zip"))
    }

    @Test fun segmentsAreSharesOfTheVolumeInCardOrder() {
        val b = StorageBreakdown(
            mapOf(
                StorageCategory.VIDEOS to CategoryUsage(300, 1),
                StorageCategory.PHOTOS to CategoryUsage(100, 1),
            ),
        )
        val seg = b.segments(1000)
        assertEquals(listOf(StorageCategory.PHOTOS, StorageCategory.VIDEOS), seg.map { it.first })
        assertEquals(0.1f, seg[0].second, 0.0001f)
        assertEquals(0.3f, seg[1].second, 0.0001f)
    }

    @Test fun tinyCategoryStaysVisibleAndEmptyOnesAreOmitted() {
        val b = StorageBreakdown(
            mapOf(
                StorageCategory.APKS to CategoryUsage(1, 1),
                StorageCategory.AUDIO to CategoryUsage(0, 0),
            ),
        )
        val seg = b.segments(1_000_000_000)
        assertEquals(1, seg.size)
        assertEquals(StorageBreakdown.MIN_SEGMENT, seg[0].second, 0.0f)
    }

    @Test fun overflowingSharesAreScaledToFitTheTrack() {
        val b = StorageBreakdown(
            mapOf(
                StorageCategory.PHOTOS to CategoryUsage(800, 1),
                StorageCategory.VIDEOS to CategoryUsage(800, 1),
            ),
        )
        val sum = b.segments(1000).sumOf { it.second.toDouble() }
        assertTrue("sum=$sum", sum <= 1.0001)
    }

    @Test fun noVolumeSizeMeansNoBar() {
        assertTrue(StorageBreakdown().segments(0).isEmpty())
        assertTrue(StorageBreakdown(mapOf(StorageCategory.PHOTOS to CategoryUsage(5, 1))).segments(-1).isEmpty())
    }

    @Test fun fileCountWording() {
        assertEquals("Empty", LandingText.fileCount(0))
        assertEquals("1 File", LandingText.fileCount(1))
        assertEquals("12 Files", LandingText.fileCount(12))
        // Still counting: say nothing rather than a wrong "Empty".
        assertEquals("", LandingText.fileCount(0, loaded = false))
    }

    @Test fun deletedCountSaturatesAtAThousand() {
        assertEquals("0", LandingText.deletedCount(0))
        assertEquals("999", LandingText.deletedCount(999))
        assertEquals("1000+", LandingText.deletedCount(1000))
        assertEquals("1000+", LandingText.deletedCount(54_321))
    }

    @Test fun compactBytesKeepsCardsNarrow() {
        assertEquals("0 B", LandingText.compactBytes(0))
        assertEquals("420 MB", LandingText.compactBytes(420L * 1024 * 1024))
        assertEquals("97 GB", LandingText.compactBytes(97L * 1024 * 1024 * 1024))
        assertEquals("9.5 GB", LandingText.compactBytes((9.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test fun usedOfTotalClampsUsedToTheVolume() {
        val gb = 1024L * 1024 * 1024
        assertEquals("97 GB/128 GB", LandingText.usedOfTotal(97 * gb, 128 * gb))
        assertEquals("128 GB/128 GB", LandingText.usedOfTotal(500 * gb, 128 * gb))
    }

    @Test fun everyCategoryTileQueryIsSomethingTheSearchParses() {
        for (category in LauncherCategory.entries) {
            val parsed = SearchQuery.parse(category.query)
            assertTrue("${category.name} query parsed to nothing", !parsed.isEmpty)
        }
        assertEquals(setOf("apk", "xapk", "apks", "apkm"), SearchQuery.parse(LauncherCategory.APKS.query).extensions)
    }

    @Test fun categoryTilesMatchTheStorageCardRows() {
        assertEquals(StorageCategory.PHOTOS, LauncherCategory.IMAGES.storage)
        assertEquals(listOf("Documents", "Images", "Videos", "APKs"), LauncherCategory.entries.map { it.label })
    }
}
