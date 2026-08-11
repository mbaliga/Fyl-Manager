package io.github.mbaliga.fylz.guard

import java.io.File

/**
 * Locates the repository on disk for guard tests that read source rather than run it.
 *
 * Android unit tests execute with the module directory (`app/`) as the working directory, but
 * that is a Gradle implementation detail, not a promise — so this ascends from wherever the
 * test starts until it finds the root `settings.gradle.kts` sitting beside `app/`. Failing to
 * find it is an error, not a skip: a guard that silently scans nothing guards nothing.
 */
internal object RepoLayout {

    val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from ${System.getProperty("user.dir")}")
    }

    /** The app module's production Kotlin tree. */
    val mainSource: File get() = root.resolve("app/src/main/java")

    /** All Kotlin files under [dir], relative-path-sorted so failures list deterministically. */
    fun kotlinFiles(dir: File): List<File> = dir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.relativeTo(root).path }
        .toList()
}
