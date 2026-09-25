package io.github.mbaliga.fylz.actions

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design §2.7 item 5: after MC.0c, every `DropdownMenuItem` and `ActionButton` call must live
 * under `ui/actions/` -- this makes "no hard-coded menus" a checked fact rather than a convention.
 * MC.0d extends the forbidden list with `ToolsRow(`/`RecoveryActionCard(`, now that both moved
 * into `ui/actions/RoomActionsRenderer.kt`.
 *
 * Word-boundary matched so `FloatingActionButton(` (a real, unrelated Material composable used by
 * the Recovery overlays) is never mistaken for `ActionButton(`.
 */
class NoHardCodedMenusTest {

    private val exemptPrefix = "io" + File.separator + "github" + File.separator + "mbaliga" +
        File.separator + "fylz" + File.separator + "ui" + File.separator + "actions" + File.separator

    private val forbidden = listOf(
        Regex("""\bDropdownMenuItem\("""),
        Regex("""\bActionButton\("""),
        Regex("""\bToolsRow\("""),
        Regex("""\bRecoveryActionCard\("""),
    )

    @Test
    fun `every DropdownMenuItem, ActionButton, ToolsRow and RecoveryActionCard call lives under ui actions`() {
        val root = findMainJavaRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(root).path.startsWith(exemptPrefix) }
            .flatMap { file ->
                val text = file.readText()
                forbidden.filter { it.containsMatchIn(text) }.map { pattern -> "${file.relativeTo(root)}: ${pattern.pattern}" }
            }
            .toList()
        assertTrue("Hard-coded menu/button calls outside ui/actions/: $offenders", offenders.isEmpty())
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
