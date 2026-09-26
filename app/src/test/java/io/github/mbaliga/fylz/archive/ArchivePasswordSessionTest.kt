package io.github.mbaliga.fylz.archive

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * `ArchivePasswordSession` (M3.9): opt-in, session-only, keyed by the archive's own `Uri` -- a
 * remembered password lets a caller skip a second prompt for that same archive, a different
 * archive is never affected, every array this hands out or replaces is wiped independently of any
 * other, and nothing about a password ever reaches `Log.*`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchivePasswordSessionTest {

    private val archiveA = Uri.parse("content://io.github.mbaliga.fylz.files/document/primary%3ADownload%2Fa.zip")
    private val archiveB = Uri.parse("content://io.github.mbaliga.fylz.files/document/primary%3ADownload%2Fb.zip")

    @Test
    fun `no password is remembered until one is explicitly remembered`() {
        assertNull(ArchivePasswordSession().passwordFor(archiveA))
    }

    @Test
    fun `remembering skips a second prompt for the same archive, a different archive still prompts`() {
        val session = ArchivePasswordSession()
        session.remember(archiveA, "sesame-open-1234".toCharArray())
        // The one thing a call site actually needs: "is there a password already?" for THIS archive.
        assertArrayEquals("sesame-open-1234".toCharArray(), session.passwordFor(archiveA))
        // A second, unrelated archive was never told anything -- it still prompts.
        assertNull(session.passwordFor(archiveB))
    }

    @Test
    fun `passwordFor hands out an independent copy every time, never the same array twice`() {
        val session = ArchivePasswordSession()
        session.remember(archiveA, "sesame-open-1234".toCharArray())
        val first = session.passwordFor(archiveA)!!
        val second = session.passwordFor(archiveA)!!
        assertNotSame("two lookups must not share one array", first, second)
        assertArrayEquals(first, second)
        // Wiping what a caller was handed (exactly what ArchiveService.createZip/extractZip already
        // does in their own `finally`) must never zero out what is remembered for next time.
        first.fill('\u0000')
        assertArrayEquals("sesame-open-1234".toCharArray(), session.passwordFor(archiveA))
    }

    @Test
    fun `remembering again for the same archive replaces the old password`() {
        val session = ArchivePasswordSession()
        session.remember(archiveA, "first-password-1".toCharArray())
        session.remember(archiveA, "second-password2".toCharArray())
        assertArrayEquals("second-password2".toCharArray(), session.passwordFor(archiveA))
    }

    @Test
    fun `forgetting an archive's password makes the next lookup prompt again`() {
        val session = ArchivePasswordSession()
        session.remember(archiveA, "sesame-open-1234".toCharArray())
        session.forget(archiveA)
        assertNull(session.passwordFor(archiveA))
        // Forgetting an archive with nothing remembered is a harmless no-op.
        session.forget(archiveB)
    }

    @Test
    fun `nothing about a password ever reaches Log`() {
        ShadowLog.clear()
        val session = ArchivePasswordSession()
        val secret = "correct-horse-battery-staple"
        session.remember(archiveA, secret.toCharArray())
        repeat(3) { session.passwordFor(archiveA) }
        session.remember(archiveA, "a-replacement-password".toCharArray())
        session.forget(archiveA)
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg}" }
        assertFalse("the password itself must never appear in a log line", logged.contains(secret))
        assertFalse("nothing here should even mention \"password\"", logged.contains("password", ignoreCase = true))
    }
}
