package io.github.mbaliga.fylz.formats.inspect

import io.github.mbaliga.fylz.formats.FormatInspector

/** Every media inspector: audio, video and music containers. */
object MediaInspectors {
    val all: List<FormatInspector> = listOf(
        MediaInspectorsMp3, MediaInspectorsWav, MediaInspectorsFlac, MediaInspectorsOgg, MediaInspectorsMp4,
        MediaInspectorsMkv, MediaInspectorsAvi, MediaInspectorsFlv, MediaInspectorsMidi, MediaInspectorsAiff,
    )
}
