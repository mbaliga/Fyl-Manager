package io.github.mbaliga.fylz.storage

import android.content.Context
import android.content.Intent
import io.github.mbaliga.fylz.core.model.ItemCapability

/**
 * The capability adapter: one interface behind which the `java.io.File`-backed source and the
 * SAF-backed source coexist.
 *
 * Which implementation leads is a *runtime* decision made by [StorageAccess] based on whether
 * "All files access" is currently granted -- not a compile-time one.
 */
interface StorageProvider {
    /** Stable identifier, e.g. `saf` or `file`. */
    val id: String

    /** What this provider supports; the UI branches on this rather than on the flavor name. */
    val capabilities: Set<ItemCapability>

    /**
     * True when this provider's roots can be listed and walked without any user gesture.
     *
     * Describes the provider's *launch-surface* behavior -- how a root is reached -- not
     * anything about an item's operations once reached, so it lives here rather than in
     * [capabilities]. See [ItemCapability]'s KDoc for why it was deliberately left out of that
     * vocabulary.
     */
    val browseWithoutPicker: Boolean get() = false

    /** True when this provider exposes whole storage volumes rather than individually granted subtrees. */
    val wholeVolume: Boolean get() = false

    /**
     * True when the provider can enumerate roots right now.
     *
     * The SAF provider is always ready. The File-backed provider is ready only once
     * `MANAGE_EXTERNAL_STORAGE` has been granted.
     */
    fun isReady(context: Context): Boolean

    /**
     * A short sentence explaining what the user must do when [isReady] is false, or null when
     * nothing is required.
     */
    fun readinessMessage(context: Context): String?

    /**
     * Intent that takes the user to the screen where they can satisfy [isReady], or null when the
     * provider needs no system-level grant.
     */
    fun permissionIntent(context: Context): Intent?

    /**
     * The launch surface: everything the user can open without picking a folder first, plus any
     * picker shortcuts that still need one.
     *
     * Must be safe to call from a coroutine on a background dispatcher; implementations perform
     * disk and `ContentResolver` work.
     */
    suspend fun rootGroups(context: Context): List<StorageRootGroup>
}
