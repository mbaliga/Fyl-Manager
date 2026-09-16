package io.github.mbaliga.fylz.data

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MediaTrackOptions] turns a live [Tracks] snapshot into UI-facing options and back into a real
 * override -- [Tracks]/[TrackGroup]/[Format] are plain data holders with public constructors, not
 * native-backed, so these tests build real instances directly rather than faking the type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaTrackOptionsTest {

    private fun textFormat(language: String?, label: String? = null) =
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(language).setLabel(label).build()

    private fun audioFormat(language: String?) =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).setLanguage(language).build()

    private fun group(format: Format, selected: Boolean, supported: Boolean = true) = Tracks.Group(
        TrackGroup(format),
        false,
        intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE),
        booleanArrayOf(selected),
    )

    @Test
    fun `lists only tracks of the requested kind, in group order`() {
        val tracks = Tracks(
            listOf(
                group(textFormat("en", "English"), selected = true),
                group(audioFormat("en"), selected = true),
                group(textFormat("fr", "French"), selected = false),
            ),
        )

        val subtitles = MediaTrackOptions.listFor(tracks, MediaTrackKind.SUBTITLE)

        assertEquals(2, subtitles.size)
        assertEquals("English", subtitles[0].label)
        assertTrue(subtitles[0].isSelected)
        assertEquals("French", subtitles[1].label)
        assertFalse(subtitles[1].isSelected)
    }

    @Test
    fun `falls back to the language, then a generic ordinal, when there is no label`() {
        val tracks = Tracks(
            listOf(
                group(textFormat("es"), selected = false),
                group(textFormat(null), selected = false),
            ),
        )

        val subtitles = MediaTrackOptions.listFor(tracks, MediaTrackKind.SUBTITLE)

        assertEquals("ES", subtitles[0].label)
        assertEquals("Subtitle 2", subtitles[1].label)
    }

    @Test
    fun `an unsupported track is still listed, just flagged`() {
        val tracks = Tracks(listOf(group(textFormat("en", "English"), selected = false, supported = false)))

        val subtitles = MediaTrackOptions.listFor(tracks, MediaTrackKind.SUBTITLE)

        assertFalse(subtitles.single().isSupported)
    }

    @Test
    fun `hasSelection is true only once a track of that kind is actually selected`() {
        val noneSelected = Tracks(listOf(group(textFormat("en", "English"), selected = false)))
        val oneSelected = Tracks(listOf(group(textFormat("en", "English"), selected = true)))

        assertFalse(MediaTrackOptions.hasSelection(noneSelected, MediaTrackKind.SUBTITLE))
        assertTrue(MediaTrackOptions.hasSelection(oneSelected, MediaTrackKind.SUBTITLE))
    }

    @Test
    fun `overrideFor points at the option's own group and track index`() {
        val englishGroup = group(textFormat("en", "English"), selected = true)
        val tracks = Tracks(listOf(group(audioFormat("en"), selected = true), englishGroup))
        val option = MediaTrackOptions.listFor(tracks, MediaTrackKind.SUBTITLE).single()

        val override = MediaTrackOptions.overrideFor(tracks, option)

        assertEquals(englishGroup.mediaTrackGroup, override.mediaTrackGroup)
        assertEquals(listOf(0), override.trackIndices)
    }
}
