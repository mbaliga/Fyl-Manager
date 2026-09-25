package io.github.mbaliga.fylz.storage

import android.content.Context
import android.content.pm.ProviderInfo
import io.github.mbaliga.fylz.archive.ArchiveCacheSweeper
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveEntryCache
import io.github.mbaliga.fylz.archive.ArchiveSource
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hosts the real [ArchiveDocumentsProvider] over [FakeArchiveDecoder] for the M3.3b tests
 * (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.10).
 *
 * The design asks for the provider to be attached with **the manifest's** `ProviderInfo`
 * (`packageManager.resolveContentProvider(AUTHORITY, 0)`) so the exported/permission contract is
 * what `DocumentsProvider.attachInfo` checks. This project's unit tests do not load the merged
 * manifest into Robolectric's `PackageManager` (`includeAndroidResources` is off, which is also why
 * `ProviderTestSupport.testProviderInfo` exists), so [manifestProviderInfo] uses the package
 * manager's answer when there is one and otherwise reads the `<provider>` element straight from
 * `app/src/main/AndroidManifest.xml` -- the same attributes, from the same file, and `attachInfo`
 * still refuses anything short of exported + grantUriPermissions + both MANAGE_DOCUMENTS
 * permissions. A device run resolves it through the package manager.
 */
internal object ArchiveProviderTestSupport {

    class Hosted(
        val provider: ArchiveDocumentsProvider,
        val stub: FakeArchiveDecoder,
        val sweeper: ArchiveCacheSweeper,
        val entryCache: ArchiveEntryCache,
        var catalog: ArchiveCatalog,
    ) {
        /** A fresh catalog with nothing in memory: what a cold process has. Disk listings stay. */
        fun coldCatalog(): ArchiveCatalog {
            catalog = newCatalog(RuntimeEnvironment.getApplication(), stub, entryCache, sweeper)
            provider.catalogOverride = catalog
            return catalog
        }
    }

    private val limits = ArchiveLimits()

    /** Robolectric's `StatFs` reports no free space: every space check gets an injected answer. */
    private val plenty: () -> Long? = { Long.MAX_VALUE }

    fun newCatalog(context: Context, stub: FakeArchiveDecoder, entryCache: ArchiveEntryCache, sweeper: ArchiveCacheSweeper): ArchiveCatalog =
        ArchiveCatalog(
            context,
            ArchiveSource(context, limits, availableCacheBytes = plenty),
            FakeArchiveDecoder.client(stub),
            limits,
            entryCache,
            sweeper,
        )

    fun host(stub: FakeArchiveDecoder = FakeArchiveDecoder()): Hosted {
        val context = RuntimeEnvironment.getApplication()
        val sweeper = ArchiveCacheSweeper(context)
        val entryCache = ArchiveEntryCache(context, FakeArchiveDecoder.client(stub), limits, sweeper, availableCacheBytes = plenty)
        val catalog = newCatalog(context, stub, entryCache, sweeper)
        val info = manifestProviderInfo(context)
        val provider = Robolectric.buildContentProvider(ArchiveDocumentsProvider::class.java).create(info).get()
        provider.catalogOverride = catalog
        provider.entryCacheOverride = entryCache
        // Robolectric runs tests on the main looper; a device never calls a provider there.
        provider.mainThreadGuard = {}
        registerForContentResolver(provider, ArchiveDocumentsProvider.AUTHORITY)
        return Hosted(provider, stub, sweeper, entryCache, catalog)
    }

    /** The manifest's `<provider>` for the archive authority, as a [ProviderInfo]. */
    fun manifestProviderInfo(context: Context): ProviderInfo {
        context.packageManager.resolveContentProvider(ArchiveDocumentsProvider.AUTHORITY, 0)?.let { return it }
        val manifest = findManifest()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val providers = document.getElementsByTagName("provider")
        val element = (0 until providers.length).map { providers.item(it) }
            .firstOrNull { it.attributes.getNamedItem("android:authorities")?.nodeValue == ArchiveDocumentsProvider.AUTHORITY }
            ?: error("no <provider> for ${ArchiveDocumentsProvider.AUTHORITY} in ${manifest.path}")
        fun attribute(name: String): String? = element.attributes.getNamedItem("android:$name")?.nodeValue
        val hasIntentFilter = (0 until element.childNodes.length).any { element.childNodes.item(it).nodeName == "intent-filter" }
        check(!hasIntentFilter) { "the archive provider must not carry a DOCUMENTS_PROVIDER intent filter" }
        return ProviderInfo().apply {
            packageName = context.packageName
            name = attribute("name") ?: error("provider has no android:name")
            authority = attribute("authorities")
            exported = attribute("exported") == "true"
            grantUriPermissions = attribute("grantUriPermissions") == "true"
            readPermission = attribute("permission")
            writePermission = attribute("permission")
        }
    }

    private fun findManifest(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            for (relative in listOf("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")) {
                val candidate = dir?.let { File(it, relative) }
                if (candidate?.isFile == true) return candidate
            }
            dir = dir?.parentFile
        }
        error("AndroidManifest.xml not found from ${System.getProperty("user.dir")}")
    }
}
