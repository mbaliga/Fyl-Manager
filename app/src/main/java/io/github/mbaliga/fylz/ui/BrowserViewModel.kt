package io.github.mbaliga.fylz.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.mbaliga.fylz.browse.SessionSnapshot
import io.github.mbaliga.fylz.browse.SessionStore
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.browse.decodeSession
import io.github.mbaliga.fylz.browse.encodeSession
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.FylzClipboard
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ViewMode
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Owns the browsing session (P1.10): tabs, each one's full navigation stack, the active tab,
 * selection, sort, view and preview mode, search, and the clipboard -- previously local
 * `remember{}` state inside `FylzV1Workspace`, torn down on every rotation, fold or process death.
 * The P0.5 `android:configChanges` line on `MainActivity` papered over rotation/fold specifically;
 * this replaces it with the real thing, per that line's own comment ("P1.10 removes this line
 * again"). One `BrowserViewModel` outlives the Activity that reads it the way any ViewModel does:
 * alive across a config change, gone only when the Activity finishes for good.
 *
 * `FylzV1Workspace` reads/writes every field below through a local `by viewModel::x` property
 * delegate (Kotlin's "delegate to another property" feature) rather than changing its own
 * mutation call sites -- `tabs[index] = ...`, `activeTabId = id` and so on read exactly as they did
 * before this task, they just resolve through to this class instead of a `remember{}` block.
 *
 * Two persistence layers, not one:
 * - [savedStateHandle] carries the encoded session through anything the platform's own
 *   `onSaveInstanceState` machinery already covers -- config changes, and a process death the OS
 *   chooses to restore (exactly the "Don't keep activities" device check this task adds).
 * - [SessionStore] is the durable fallback for everything that isn't: a genuinely cold launch,
 *   after the task was swiped away or the process was gone long enough that the OS discarded the
 *   saved instance state. Both are written from the same [persist] call, so they never disagree
 *   about the current session; [savedStateHandle] is checked first in [restoreSession] since it is
 *   always at least as fresh as [sessionStore]'s own last write.
 *
 * [FylzClipboard] is deliberately NOT part of [SessionSnapshot]: it holds a snapshot of
 * [io.github.mbaliga.fylz.model.FileEntry] values, not just their Uris, and neither persistence
 * layer has a codec for that today. A cut/copy that doesn't survive a forced process kill is a
 * real, narrow gap -- recorded in `docs/agent/PROGRESS.md` rather than silently left uncovered.
 * [io.github.mbaliga.fylz.model.FileEntry]-level single-item preview focus (`focusedEntry` in
 * `FylzV1Workspace`) is the other piece P1.10's own state list doesn't literally name ("selection"
 * meaning the multi-select [selectedUris] batch actions use, not a single open preview); it stays
 * local, transient Compose state, also noted in the progress log.
 */
class BrowserViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val sessionStore = SessionStore(application)

    val tabs = mutableStateListOf<FolderTab>()
    var activeTabId by mutableStateOf<String?>(null)
    var selectedUris by mutableStateOf<Set<Uri>>(emptySet())
    var sortSpec by mutableStateOf(SortSpec.Default)
    var viewMode by mutableStateOf(ViewMode.LIST)
    var previewMode by mutableStateOf(PreviewMode.DOCKED)
    var query by mutableStateOf("")
    var searchRecursive by mutableStateOf(false)
    var clipboard by mutableStateOf<FylzClipboard?>(null)

    init {
        restoreSession()
        // One watcher for every persisted field, rather than a persist() call at each of the
        // dozen-plus mutation sites `FylzV1Workspace` already has -- snapshotFlow re-emits whenever
        // any Compose state read inside the block changes, so adding a new persisted field later is
        // one line here, not a new call site wherever that field happens to be mutated.
        viewModelScope.launch {
            snapshotFlow { currentSnapshot() }
                .debounce(PERSIST_DEBOUNCE_MS)
                .collect { snapshot -> persist(snapshot) }
        }
    }

    private fun currentSnapshot() = SessionSnapshot(
        tabs = tabs.toList(),
        activeTabId = activeTabId,
        sortSpec = sortSpec,
        viewMode = viewMode,
        previewMode = previewMode,
        query = query,
        searchRecursive = searchRecursive,
    )

    private fun persist(snapshot: SessionSnapshot) {
        val json = encodeSession(snapshot)
        savedStateHandle[KEY_SESSION] = json
        sessionStore.save(json)
    }

    /**
     * A tab is only ever restored while its [FolderTab.treeUri] still has a live read grant --
     * the OS can revoke a SAF permission at any time (the volume was unmounted, the user revoked
     * it in Settings), and restoring a tab the app can no longer actually read would just crash
     * the first thing that tries. Capped at [MAX_RESTORED_TABS] for the same reason the old
     * `OpenTabsStore`-based restore was: opening dozens of tabs from a single restore is far more
     * likely a corrupt/runaway session than something a real user built by hand.
     */
    private fun restoreSession() {
        val json: String? = savedStateHandle[KEY_SESSION] ?: sessionStore.restore()
        val snapshot = json?.let(::decodeSession) ?: return
        val livePermissions = getApplication<Application>().contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapTo(mutableSetOf()) { it.uri }
        val restoredTabs = snapshot.tabs.filter { it.treeUri in livePermissions }.take(MAX_RESTORED_TABS)
        tabs.addAll(restoredTabs)
        activeTabId = snapshot.activeTabId?.takeIf { id -> restoredTabs.any { it.id == id } }
        sortSpec = snapshot.sortSpec
        viewMode = snapshot.viewMode
        previewMode = snapshot.previewMode
        query = snapshot.query
        searchRecursive = snapshot.searchRecursive
    }

    private companion object {
        const val KEY_SESSION = "browser_session"
        const val PERSIST_DEBOUNCE_MS = 300L

        /** How many previously granted SAF subtrees are restored as tabs on launch. */
        const val MAX_RESTORED_TABS = 8
    }
}
