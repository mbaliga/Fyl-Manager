package io.github.mbaliga.fylz.storage

import android.content.Context
import android.content.Intent
import io.github.mbaliga.fylz.core.model.ItemCapability
import io.github.mbaliga.fylz.network.RemoteConnectionStore

/**
 * Surfaces the user's saved network locations (WebDAV/SFTP/SMB/S3) on the storage home screen,
 * next to internal storage and removable volumes rather than behind a plain "Remote locations"
 * button that looked identical whether zero connections were saved or ten.
 *
 * Always ready and needs no system permission -- a remote connection's own credential grant
 * already happened when the user saved it. Every root this returns carries
 * [StorageRootKind.REMOTE] and, deliberately, neither a [StorageRoot.treeUri] nor a
 * [StorageRoot.documentUri]: browsing a remote connection runs through [io.github.mbaliga.fylz.network.RemoteBrowser]'s
 * own protocol clients, never Android's Storage Access Framework, so there is no SAF uri to give
 * it. [StorageHomeScreen]'s click dispatch routes REMOTE roots to `onOpenRoot` unconditionally
 * rather than through the `opensDirectly` SAF/picker split that every other root uses.
 *
 * The navigation this enables is deliberately modest: tapping a row opens the same
 * `RemoteConnectionsDialog` the app already had, now focused on that one connection instead of
 * its bare list. It does not give remote locations a tab in the main file browser -- that would
 * mean routing copy/move/rename/delete through providers that do not implement them yet
 * ([io.github.mbaliga.fylz.network.RemoteCapability]'s MOVE/COPY/RENAME have no real backing today).
 */
class RemoteStorageProvider : StorageProvider {

    override val id: String = ID

    // Listing is the one thing a saved connection can actually do through this surface today.
    // Claiming more here would be exactly the "capability exists in name only" trap this app has
    // hit before.
    override val capabilities: Set<ItemCapability> = setOf(ItemCapability.LIST)

    override val browseWithoutPicker: Boolean = true

    override fun isReady(context: Context): Boolean = true

    override fun readinessMessage(context: Context): String? = null

    override fun permissionIntent(context: Context): Intent? = null

    override suspend fun rootGroups(context: Context): List<StorageRootGroup> {
        val connections = RemoteConnectionStore(context).list()
        if (connections.isEmpty()) return emptyList()
        val roots = connections.map { connection ->
            StorageRoot(
                id = rootId(connection.id),
                title = connection.displayName,
                subtitle = connection.kind.label,
                kind = StorageRootKind.REMOTE,
            )
        }
        return listOf(StorageRootGroup(GROUP_REMOTE, roots))
    }

    companion object {
        const val ID: String = "remote"
        const val GROUP_REMOTE: String = "Remote locations"

        fun rootId(connectionId: String): String = "$ID:$connectionId"

        /** The inverse of [rootId] -- kept beside it so the two cannot drift apart. */
        fun connectionIdOf(rootId: String): String = rootId.substringAfter(':')
    }
}
