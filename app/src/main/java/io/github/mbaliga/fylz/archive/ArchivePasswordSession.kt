package io.github.mbaliga.fylz.archive

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * The shared, session-only password memory every archive password prompt draws from (M3.9,
 * MASTER_PLAN "Password prompt"): opt-in, in-memory only, keyed by the archive's own document
 * [Uri] (never a catalog key by design -- both zip4j call sites [ArchivePasswordSession] serves
 * today, `io.github.mbaliga.fylz.ui.ArchiveToolsOverlay`'s own EXTRACT purpose and `FylzV1App`'s
 * legacy-encrypted-ZIP flow, only ever have a plain document `Uri` in hand, never a listed
 * `ArchiveHandle.key`). Nothing here is written to disk, and nothing here is ever logged; the map
 * itself dies with the process, which is the only "clearing" a session-only secret needs.
 *
 * A password is remembered only when the person ticks "Remember for this session" in the prompt
 * (`io.github.mbaliga.fylz.ui.components.PasswordPromptDialog`'s own checkbox); left unticked (the
 * default), every prompt is a fresh ask and nothing is stored here at all. [remember] and
 * [passwordFor] always copy: the caller's own array and the session's stored one are never the
 * same object, so a caller that wipes what it was handed (`io.github.mbaliga.fylz.data
 * .ArchiveService.createZip`/`extractZip` both do, in their own `finally`, exactly as every other
 * password in this app) never zeroes out what is remembered for next time, and [forget] wipes only
 * the session's own copy.
 *
 * One instance lives on [io.github.mbaliga.fylz.FylzApplication], a [ConcurrentHashMap] because
 * both call sites above read and write it from Compose's own UI thread, but a background test (or
 * a future caller off it) must see the same, single map -- the same reasoning
 * [ArchiveEncodingOverrides] already gives for its own session-only map.
 */
class ArchivePasswordSession {
    private val remembered = ConcurrentHashMap<Uri, CharArray>()

    /**
     * A fresh copy of the password remembered for [archive], or `null` when none was kept. The
     * caller owns this copy from here on and must wipe it once done, exactly as it would any other
     * password in this app; wiping it never disturbs what is remembered for next time.
     */
    fun passwordFor(archive: Uri): CharArray? = remembered[archive]?.copyOf()

    /**
     * Remembers a copy of [password] for [archive] for the rest of this process's life, wiping
     * whatever was remembered for it before. Takes no ownership of [password] itself -- the caller
     * still wipes its own array exactly as if nothing were remembered at all.
     */
    fun remember(archive: Uri, password: CharArray) {
        remembered.put(archive, password.copyOf())?.fill('\u0000')
    }

    /** Forgets [archive]'s remembered password, if any, wiping the session's own copy first. */
    fun forget(archive: Uri) {
        remembered.remove(archive)?.fill('\u0000')
    }
}
