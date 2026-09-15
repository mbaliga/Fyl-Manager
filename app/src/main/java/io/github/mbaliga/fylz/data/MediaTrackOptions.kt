package io.github.mbaliga.fylz.data

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

/** The three track families a [Tracks] snapshot can be filtered to. */
enum class MediaTrackKind(val trackType: Int) {
    SUBTITLE(C.TRACK_TYPE_TEXT),
    AUDIO(C.TRACK_TYPE_AUDIO),
    VIDEO(C.TRACK_TYPE_VIDEO),
}

/** One selectable track surfaced from a live [Tracks] snapshot, plus the coordinates
 * ([groupIndex]/[trackIndex]) [MediaTrackOptions.overrideFor] needs to build a real selection. */
data class MediaTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean,
    val isSupported: Boolean,
)

/**
 * Turns a live [androidx.media3.exoplayer.ExoPlayer.getCurrentTracks] snapshot into the plain list
 * a settings UI picks from, and turns a picked [MediaTrackOption] back into the override
 * [androidx.media3.common.Player.setTrackSelectionParameters] actually needs -- kept separate from
 * [io.github.mbaliga.fylz.ui.components.MediaFilePreview] so this mapping is unit-testable on its
 * own; [Tracks]/[androidx.media3.common.TrackGroup]/[Format] are plain data holders with public
 * constructors, not native-backed, so a test can build a real [Tracks] instance directly.
 */
object MediaTrackOptions {

    fun listFor(tracks: Tracks, kind: MediaTrackKind): List<MediaTrackOption> {
        val options = mutableListOf<MediaTrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != kind.trackType) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                options += MediaTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = labelFor(group.getTrackFormat(trackIndex), kind, options.size),
                    isSelected = group.isTrackSelected(trackIndex),
                    isSupported = group.isTrackSupported(trackIndex),
                )
            }
        }
        return options
    }

    /** True once any track of [kind] is actually selected -- distinct from "off", which for
     * subtitles is a real, deliberate state rather than merely "nothing chosen yet". */
    fun hasSelection(tracks: Tracks, kind: MediaTrackKind): Boolean =
        tracks.groups.any { it.type == kind.trackType && (0 until it.length).any(it::isTrackSelected) }

    fun overrideFor(tracks: Tracks, option: MediaTrackOption) =
        androidx.media3.common.TrackSelectionOverride(tracks.groups[option.groupIndex].mediaTrackGroup, option.trackIndex)

    private fun labelFor(format: Format, kind: MediaTrackKind, ordinal: Int): String {
        val language = format.language?.takeIf { it != "und" }
        val name = format.label ?: language?.uppercase()
        val fallbackKind = kind.name.lowercase().replaceFirstChar(Char::uppercase)
        return name ?: "$fallbackKind ${ordinal + 1}"
    }
}
