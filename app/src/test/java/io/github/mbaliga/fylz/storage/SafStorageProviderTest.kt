package io.github.mbaliga.fylz.storage

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `providerRoots` used to lump the platform MTP provider in with the shell provider as "never
 * exposes anything a user browses". True for shell (a development surface); wrong for MTP, which
 * is the real route to a camera, phone or e-reader connected over USB -- exactly how Android's own
 * Files app reaches those devices. This pins the fix: MTP now gets a labelled, browsable row;
 * shell still doesn't.
 *
 * `shadows = [ShadowFullAccessEnvironment::class]` (declared alongside
 * [FileStorageProviderRootGroupsTest]) is only here because [rootGroups] unconditionally computes
 * `StorageAccess.hasFullAccess` before branching, and unshadowed
 * `Environment.isExternalStorageManager()` throws under Robolectric -- the file/USB distinction
 * under test does not otherwise depend on which way that flag reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowFullAccessEnvironment::class])
class SafStorageProviderTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun registerContentProvider(authority: String, packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            providerInfo = ProviderInfo().apply {
                this.authority = authority
                this.packageName = packageName
                name = "$authority.Provider"
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
        }
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE), resolveInfo)
    }

    @Test
    fun `a connected MTP device surfaces as a browsable USB row`() = runBlocking {
        registerContentProvider(SafStorageProvider.MTP_AUTHORITY, packageName = "com.android.mtp")

        val groups = SafStorageProvider().rootGroups(context)

        val usbRoot = groups.flatMap { it.roots }.singleOrNull { it.kind == StorageRootKind.USB_DEVICE }
        requireNotNull(usbRoot) { "expected a USB_DEVICE root for the MTP authority" }
        assertEquals("USB devices", usbRoot.title)
    }

    @Test
    fun `the shell provider is still filtered out as noise`() = runBlocking {
        registerContentProvider("com.android.shell.documents", packageName = "com.android.shell")

        val groups = SafStorageProvider().rootGroups(context)

        val shellRoot = groups.flatMap { it.roots }.firstOrNull { it.id.contains("shell") }
        assertNull(shellRoot)
    }
}
