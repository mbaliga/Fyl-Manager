package io.github.mbaliga.fylz.guard

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FylzTheme` is the only thing allowed to invoke `MaterialTheme(...)`.
 *
 * The bare-`MaterialTheme` bug has shipped three times: a new Activity calls `setContent { }`,
 * wraps its content in `MaterialTheme { }` out of habit, and arrives light inside an otherwise
 * dark app because it never composed inside [io.github.mbaliga.fylz.ui.theme.FylzTheme]. Code
 * review caught it zero times out of three, so the rule is enforced structurally: this test
 * scans the production source tree and fails on any `MaterialTheme(` invocation outside the one
 * file that owns the theme.
 *
 * The allowed file is `ui/theme/FylzTheme.kt` — the wrapper `FylzV1App` composes — because that
 * is where the app's single `MaterialTheme(...)` call legitimately lives. Reads like
 * `MaterialTheme.colorScheme` are fine anywhere; only the invocation (the paren) is claimed.
 *
 * It runs as a plain unit test on purpose: `testDebugUnitTest` is in both CI task lists, so the
 * guard gates debug and release builds without a custom lint module to maintain.
 */
class ThemeOwnershipGuardTest {

    @Test
    fun `MaterialTheme is invoked only by FylzTheme`() {
        val offences = RepoLayout.kotlinFiles(RepoLayout.mainSource)
            .filterNot { it.invariantPath().endsWith(ALLOWED_OWNER) }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (INVOCATION.containsMatchIn(line)) {
                        "${file.relativeTo(RepoLayout.root).invariantPath()}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            buildString {
                appendLine("MaterialTheme(...) may only be invoked inside $ALLOWED_OWNER.")
                appendLine("Compose new screens inside FylzTheme (via FylzV1App) instead — a bare")
                appendLine("MaterialTheme ships the app's colours wrong. Offending lines:")
                offences.forEach { appendLine("  $it") }
            },
            offences.isEmpty(),
        )
    }

    private fun java.io.File.invariantPath() = path.replace('\\', '/')

    private companion object {
        const val ALLOWED_OWNER = "ui/theme/FylzTheme.kt"

        /**
         * The invocation, not the object: `MaterialTheme(` with optional whitespace before the
         * paren. Property reads (`MaterialTheme.colorScheme`) never match, and the word boundary
         * keeps `FylzMaterialThemeSomething(` from matching if one ever exists.
         */
        val INVOCATION = Regex("""\bMaterialTheme\s*\(""")
    }
}
