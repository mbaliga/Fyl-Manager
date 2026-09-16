package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The preview browser's path into an archive's bytes -- unencrypted zip stays on the fast JDK
 * stream it always used; a password now routes through zip4j instead, the same library and the
 * same AES support [ArchiveService] writes with, so an archive Fylz encrypted is guaranteed
 * openable here too. Nothing about this class had a test before this password path existed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveEntryReaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun buildZip(encrypted: Boolean, password: CharArray? = null): File {
        val zip = temporaryFolder.newFile("archive-${if (encrypted) "locked" else "plain"}.zip")
        zip.delete()
        val entry = temporaryFolder.newFile("secret.txt").apply { writeText("the treasure is real") }
        val parameters = ZipParameters().apply {
            if (encrypted) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        val zipFile = if (encrypted) ZipFile(zip, password) else ZipFile(zip)
        zipFile.addFile(entry, parameters)
        return zip
    }

    private fun member(archive: File): ArchiveMember =
        runBlocking { ArchiveEntryReader(context).list(Uri.fromFile(archive), archive.name) }
            .members.first { !it.directory }

    @Test
    fun `an unencrypted member extracts with no password`() = runBlocking {
        val archive = buildZip(encrypted = false)
        val extracted = ArchiveEntryReader(context).extract(Uri.fromFile(archive), archive.name, member(archive))
        assertEquals("the treasure is real", extracted.readText())
    }

    @Test
    fun `an encrypted member extracts with the correct password`() = runBlocking {
        val password = "correct-horse".toCharArray()
        val archive = buildZip(encrypted = true, password = password)
        val extracted = ArchiveEntryReader(context)
            .extract(Uri.fromFile(archive), archive.name, member(archive), password = password)
        assertEquals("the treasure is real", extracted.readText())
    }

    @Test
    fun `the wrong password fails cleanly rather than returning garbage`() = runBlocking {
        val archive = buildZip(encrypted = true, password = "correct-horse".toCharArray())
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ArchiveEntryReader(context).extract(
                    Uri.fromFile(archive),
                    archive.name,
                    member(archive),
                    password = "wrong-guess".toCharArray(),
                )
            }
        }
        assertEquals("Incorrect password, or this archive is corrupt.", failure.message)
    }

    @Test
    fun `an encrypted member with no password supplied fails rather than extracting`() {
        val archive = buildZip(encrypted = true, password = "correct-horse".toCharArray())
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ArchiveEntryReader(context).extract(Uri.fromFile(archive), archive.name, member(archive))
            }
        }
    }
}
