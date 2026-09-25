package io.github.mbaliga.fylz.core

/**
 * Idiomatic entry point over `fylz_ffi_android.kt`'s generated bindings. Callers use this
 * object, never the generated top-level `fylzVersion`/`sniff`/`archive*` functions directly, so a
 * future uniffi regeneration (renamed or added generated symbols) never ripples past this file.
 */
object FylzCore {
    fun version(): String = fylzVersion()

    /** Content-type detection (`fylz-sniff`, M2.5); see `sniff`'s own KDoc in the generated file. */
    suspend fun sniffFile(pathFd: Int): String = sniff(pathFd)

    /**
     * Archive inspection (`fylz-archive`, M3.2b): one header pass over the regular file open at
     * [fd] plus the extraction policy, with the first [maxRows] entries. Synchronous, as the Rust
     * function is -- it runs on a Binder thread in the decoder process that has nothing else to
     * do. Throws the generated `ArchiveEngineException` for the engine's own verdicts.
     */
    @Throws(ArchiveEngineException::class)
    fun inspectArchive(fd: Int, limits: ArchiveLimitsRecord, maxRows: Int): ArchiveInspectionRecord =
        archiveInspect(fd, limits, maxRows.coerceAtLeast(0).toUInt())

    /**
     * The same header pass writing the full listing into [sinkFd] as it goes (M3.3a); the record
     * comes back with no rows, and `partial` set when the pass stopped on damage after some
     * entries. Neither descriptor is closed here.
     */
    @Throws(ArchiveEngineException::class)
    fun listArchive(fd: Int, limits: ArchiveLimitsRecord, sinkFd: Int): ArchiveInspectionRecord =
        archiveListInto(fd, limits, sinkFd)

    /**
     * Streams the entry at raw header [ordinal] (whose path must be exactly [expectedPath]) into
     * [sinkFd] under the caps in [limits] and returns the bytes written (M3.3a). A negative
     * ordinal can never match a header, so it is refused as `NotFound` by the engine.
     */
    @Throws(ArchiveEngineException::class)
    fun extractEntryAt(fd: Int, ordinal: Int, expectedPath: String, limits: ArchiveLimitsRecord, sinkFd: Int): Long =
        archiveExtractEntryAt(fd, if (ordinal < 0) UInt.MAX_VALUE else ordinal.toUInt(), expectedPath, limits, sinkFd).toLong()
}
