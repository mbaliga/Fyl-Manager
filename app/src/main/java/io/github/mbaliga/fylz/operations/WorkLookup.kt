package io.github.mbaliga.fylz.operations

import android.content.Context
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * "Is the work tagged [tag] still alive?" -- the one WorkManager question `OperationRunner.recover`
 * and `ArchiveExtractor`'s claim ask (design section 2.3 steps 1 and 9), behind an interface so
 * the JVM tests answer it without a `WorkManager` (and the real answer needs an initialised one).
 * Returns the ids of every request with the tag that has not finished (ENQUEUED, RUNNING or
 * BLOCKED); an uninitialised or failing WorkManager answers "nothing", which recovery reads as
 * "gone" -- the conservative reading, since a row it then marks is re-claimable by retry.
 */
fun interface WorkLookup {
    fun activeWorkIds(tag: String): Set<UUID>

    companion object {
        val NONE: WorkLookup = WorkLookup { emptySet() }

        fun viaWorkManager(context: Context): WorkLookup = WorkLookup { tag ->
            runCatching { WorkManager.getInstance(context.applicationContext).getWorkInfosByTag(tag).get(10, TimeUnit.SECONDS) }
                .getOrNull()
                .orEmpty()
                .filter { !it.state.isFinished }
                .map { it.id }
                .toSet()
        }
    }
}
