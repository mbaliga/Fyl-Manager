package io.github.mbaliga.fylz.storage

import android.Manifest
import android.content.ContentProvider
import android.content.pm.ProviderInfo
import android.provider.DocumentsProvider
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver

/**
 * The [ProviderInfo] fields [android.provider.DocumentsProvider.attachInfo] itself requires --
 * `exported`, `grantUriPermissions`, and both `MANAGE_DOCUMENTS` permissions -- built by hand to
 * match `FylzFilesDocumentsProvider`'s real manifest `<provider>` entry.
 *
 * `Robolectric.setupContentProvider(Class)` (the no-authority overload) does not read this from
 * the merged manifest the way its name suggests -- in this Robolectric version it passes a null
 * [ProviderInfo] straight through, which crashes `DocumentsProvider.attachInfo` with an NPE
 * before it ever reaches the security checks. Every test that attaches a real
 * [android.provider.DocumentsProvider] subclass in this suite goes through this instead.
 */
internal fun testProviderInfo(
    providerClass: Class<out DocumentsProvider>,
    authority: String = FylzFilesDocumentsProvider.AUTHORITY,
): ProviderInfo = ProviderInfo().apply {
    packageName = RuntimeEnvironment.getApplication().packageName
    name = providerClass.name
    this.authority = authority
    exported = true
    grantUriPermissions = true
    readPermission = Manifest.permission.MANAGE_DOCUMENTS
    writePermission = Manifest.permission.MANAGE_DOCUMENTS
}

/**
 * Registers [provider] under [authority] with `ShadowContentResolver`, so plain
 * `context.contentResolver` calls -- as opposed to calling methods on the provider instance
 * directly -- route to it. Attaching a provider (`ContentProviderController.create`) doesn't
 * reliably do this on its own in every code path this suite uses, so tests that go through
 * `ContentResolver` (like `FileOperationService`, which only ever sees a `Context`) call this
 * explicitly after attaching.
 */
internal fun <T : ContentProvider> registerForContentResolver(provider: T, authority: String): T {
    ShadowContentResolver.registerProviderInternal(authority, provider)
    return provider
}
