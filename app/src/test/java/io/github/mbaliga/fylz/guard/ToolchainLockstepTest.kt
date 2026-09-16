package io.github.mbaliga.fylz.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The constellation's toolchain lockstep, as a failing test instead of a comment.
 *
 * Fylz, `hyle-design-system` and `shared-libraries` appear in one composite build graph. Two of
 * the pins that keep that graph resolvable were, until now, enforced only by prose:
 *
 * - **AGP** must be identical across all three builds — Gradle rejects a composite that loads
 *   two versions of the Android Gradle plugin, so drift breaks every consumer at once.
 * - **Kotlin** must be identical too. Fylz sat on 2.1.20 while both submodules built with
 *   2.1.0; it compiled by forward-compatibility luck, not by design. Luck is not a pin.
 *
 * This test reads the versions where each build declares them — the root `build.gradle.kts`
 * plugins block here, `gradle/libs.versions.toml` in each submodule — and fails on any drift,
 * in both CI task lists. Bumping is fine; bumping *together* is the rule. If the versions must
 * ever intentionally differ, that decision belongs in the owner's hands, not in a quiet edit:
 * change all three, or bring the reason to this test and rewrite its assertions with it.
 */
class ToolchainLockstepTest {

    private val root = RepoLayout.root

    @Test
    fun `kotlin version matches both submodule catalogs`() {
        val fylz = rootPluginVersion("org.jetbrains.kotlin.android")
        assertEquals("hyle-design-system pins a different Kotlin", catalogVersion(HYLE, "kotlin"), fylz)
        assertEquals("shared-libraries pins a different Kotlin", catalogVersion(SHARED, "kotlin"), fylz)
    }

    @Test
    fun `compose compiler plugin rides the same kotlin version`() {
        assertEquals(
            "org.jetbrains.kotlin.plugin.compose must carry the Kotlin version exactly",
            rootPluginVersion("org.jetbrains.kotlin.android"),
            rootPluginVersion("org.jetbrains.kotlin.plugin.compose"),
        )
    }

    @Test
    fun `agp version matches both submodule catalogs`() {
        val fylz = rootPluginVersion("com.android.application")
        assertEquals("hyle-design-system pins a different AGP", catalogVersion(HYLE, "agp"), fylz)
        assertEquals("shared-libraries pins a different AGP", catalogVersion(SHARED, "agp"), fylz)
    }

    @Test
    fun `runtime stdlib forces match the compiler version`() {
        val kotlin = rootPluginVersion("org.jetbrains.kotlin.android")
        val appBuild = root.resolve("app/build.gradle.kts").readText()
        val forced = Regex("""org\.jetbrains\.kotlin:kotlin-stdlib[^:"]*:([0-9][^"]*)""")
            .findAll(appBuild)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("Expected kotlin-stdlib forces in app/build.gradle.kts", forced.isNotEmpty())
        forced.forEach {
            assertEquals("A forced kotlin-stdlib version drifted from the compiler", kotlin, it)
        }
    }

    /** The version literal a plugin id carries in the root plugins block. */
    private fun rootPluginVersion(pluginId: String): String {
        val text = root.resolve("build.gradle.kts").readText()
        val match = Regex("""id\("${Regex.escape(pluginId)}"\)\s+version\s+"([^"]+)"""").find(text)
        checkNotNull(match) { "Plugin $pluginId not found in root build.gradle.kts" }
        return match.groupValues[1]
    }

    /** A key's value in a submodule's `[versions]` table. */
    private fun catalogVersion(submodule: String, key: String): String {
        val catalog = root.resolve("$submodule/gradle/libs.versions.toml")
        assertTrue(
            "$catalog is missing — run: git submodule update --init --recursive",
            catalog.isFile,
        )
        val match = Regex("""(?m)^${Regex.escape(key)}\s*=\s*"([^"]+)"""").find(catalog.readText())
        checkNotNull(match) { "Version key '$key' not found in $catalog" }
        return match.groupValues[1]
    }

    private companion object {
        const val HYLE = "hyle-design-system"
        const val SHARED = "shared-libraries"
    }
}
