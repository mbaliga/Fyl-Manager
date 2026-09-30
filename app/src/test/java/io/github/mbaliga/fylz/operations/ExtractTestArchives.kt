package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveCacheSweeper
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveEntryCache
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.ArchiveSource
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeInfo
import java.io.File

/**
 * The fake archive the M3.4 planner and extractor tests share: three top-level folders (one
 * implicit, one listed after its child), a root file, a hardlink whose target is another
 * top-level item, a symlink, and a name a FAT volume cannot store. Bodies are the fixture
 * generator's LCG (`prng_bytes`), so a Kotlin test can assert content without a golden file.
 */
internal object ExtractTestArchives {
    fun prngBytes(seed: Int, size: Int): ByteArray {
        var x = seed.toLong() and 0xFFFF_FFFFL
        return ByteArray(size) {
            x = (x * 1103515245L + 12345L) and 0xFFFF_FFFFL
            ((x shr 16) and 0xFF).toByte()
        }
    }

    val readme = "# readme\n".toByteArray()
    val guide = prngBytes(7, 3_000)
    val hello = "hello world".toByteArray()
    val pixel = prngBytes(11, 256)
    val late = prngBytes(13, 40)
    val bad = "questionable".toByteArray()

    /** Ordinals, in listing order. */
    const val DOCS = 0
    const val README = 1
    const val GUIDE = 2
    const val HELLO = 3
    const val PIXEL = 4
    const val LINK = 5
    const val SYMLINK = 6
    const val LATE_X = 7
    const val LATE = 8
    const val BAD_NAME = 9

    fun sample(formatCode: Int = io.github.mbaliga.fylz.archive.ArchiveFormatFamily.ZIP, partial: Boolean = false, structuralRefusal: String? = null, encryptedHello: Boolean = false, guideDeclaredSize: Long = guide.size.toLong()) = FakeArchive(
        listOf(
            FakeArchive.Entry("docs/", kind = ArchiveEntryInfo.KIND_DIRECTORY),
            FakeArchive.Entry("docs/readme.md", readme),
            FakeArchive.Entry("docs/guide.md", guide, declaredSize = guideDeclaredSize),
            FakeArchive.Entry("hello.txt", hello, encrypted = encryptedHello),
            FakeArchive.Entry("images/pixel.bin", pixel),
            FakeArchive.Entry("docs/link-to-hello", kind = ArchiveEntryInfo.KIND_HARDLINK, linkTarget = "hello.txt"),
            FakeArchive.Entry("docs/sym", kind = ArchiveEntryInfo.KIND_SYMLINK, linkTarget = "../hello.txt"),
            FakeArchive.Entry("late/x.txt", late),
            FakeArchive.Entry("late/", kind = ArchiveEntryInfo.KIND_DIRECTORY),
            FakeArchive.Entry("bad?name.txt", bad),
        ),
        partial = partial,
        structuralRefusal = structuralRefusal,
        formatCode = formatCode,
    )

    /** What a whole-archive extraction lays down, relative path -> bytes. */
    val expectedFiles: Map<String, ByteArray> = mapOf(
        "docs/readme.md" to readme,
        "docs/guide.md" to guide,
        "docs/link-to-hello" to hello,
        "hello.txt" to hello,
        "images/pixel.bin" to pixel,
        "late/x.txt" to late,
        "bad?name.txt" to bad,
    )

    val plentyOfSpace: () -> Long? = { Long.MAX_VALUE }

    fun ext4(free: Long = 100L * 1024 * 1024 * 1024) = VolumeInfo("ext4", free, caseInsensitive = false)
    fun vfat(free: Long = 100L * 1024 * 1024 * 1024) = VolumeInfo("vfat", free, caseInsensitive = true)
    fun exfat(free: Long = 100L * 1024 * 1024 * 1024) = VolumeInfo("exfat", free, caseInsensitive = true)

    fun catalog(context: Context, stub: FakeArchiveDecoder): ArchiveCatalog {
        val limits = ArchiveLimits()
        val sweeper = ArchiveCacheSweeper(context)
        val entryCache = ArchiveEntryCache(context, FakeArchiveDecoder.client(stub), limits, sweeper, availableCacheBytes = plentyOfSpace)
        return ArchiveCatalog(context, ArchiveSource(context, limits, availableCacheBytes = plentyOfSpace), FakeArchiveDecoder.client(stub), limits, entryCache, sweeper)
    }

    /** Writes [archive] as `name` under the hosted primary root and returns its ref. */
    fun write(rootDir: File, name: String, archive: FakeArchive): ArchiveRef {
        archive.write(File(rootDir, name))
        return ArchiveRef(FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, name), emptyList())
    }

    fun treeUri(relative: String): Uri = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relative)

    /** Every regular file under [root], relative `/`-separated path -> bytes. */
    fun filesUnder(root: File): Map<String, ByteArray> = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readBytes() }
}
