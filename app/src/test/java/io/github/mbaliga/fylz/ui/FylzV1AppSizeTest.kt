package io.github.mbaliga.fylz.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design §2.7 item 6, §2.8 item 6: the MC.0 ratchet. `ui/FylzV1App.kt` held every menu, handler
 * and availability expression the registry now owns; MASTER_PLAN §2.3 requires that a refactor
 * like this leave the file smaller, not larger. The bound below is the exact line count measured
 * after MC.0f (the last commit that moves code out of this file) -- lower it whenever the file
 * shrinks further; never raise it without a recorded decision (PROGRESS.md).
 */
class FylzV1AppSizeTest {

    // The MC.0 ratchet: FylzV1App.kt's line count right after MC.0f (LegacyAvailability.kt deleted,
    // the dead shortcut policies gone). Was 2529 lines pre-MC.0.
    private val fylzV1AppMaxLines = 2287

    // FylzAppShell.kt must not grow past its MC.0e size.
    private val fylzAppShellMaxLines = 112

    private val preMc0Lines = 2529

    @Test
    fun `FylzV1App kt is at or below the MC0 ratchet and smaller than its pre-MC0 size`() {
        val lines = lineCount("FylzV1App.kt")
        assertTrue("FylzV1App.kt grew to $lines lines, past the ratchet of $fylzV1AppMaxLines", lines <= fylzV1AppMaxLines)
        assertTrue("FylzV1App.kt ($lines lines) is not smaller than its pre-MC.0 size ($preMc0Lines)", lines < preMc0Lines)
    }

    @Test
    fun `FylzAppShell kt has not grown past its MC0 size`() {
        val lines = lineCount("FylzAppShell.kt")
        assertTrue("FylzAppShell.kt grew to $lines lines, past $fylzAppShellMaxLines", lines <= fylzAppShellMaxLines)
    }

    private fun lineCount(fileName: String): Int {
        val root = findMainJavaRoot()
        val file = File(root, "io/github/mbaliga/fylz/ui/$fileName")
        return file.readLines().size
    }

    /** Robust to whatever directory Gradle happens to run the test JVM from: climbs from
     * `user.dir` looking for `app/src/main/java`, and also tries `src/main/java` directly in case
     * the working directory is already the `app` module. */
    private fun findMainJavaRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 10) {
            listOf(File(dir, "app/src/main/java"), File(dir, "src/main/java")).forEach { candidate ->
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
            depth++
        }
        error("Could not locate app/src/main/java from user.dir=${System.getProperty("user.dir")}")
    }
}
