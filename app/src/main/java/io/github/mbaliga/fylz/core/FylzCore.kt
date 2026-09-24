package io.github.mbaliga.fylz.core

/**
 * Idiomatic entry point over `fylz_ffi_android.kt`'s generated bindings. Callers use this
 * object, never the generated top-level `fylzVersion`/`sniff` functions directly, so a future
 * uniffi regeneration (renamed or added generated symbols) never ripples past this file.
 */
object FylzCore {
    fun version(): String = fylzVersion()

    /** Stub until M2.5's `fylz-sniff` lands; see `sniff`'s own KDoc in the generated file. */
    suspend fun sniffFile(pathFd: Int): String = sniff(pathFd)
}
