package io.github.mbaliga.fylz.core.format

import io.github.mbaliga.fylz.core.model.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FileFormatRegistryTest {
    @Test fun objGetsRenderedMeshPreview() {
        val value = FileFormatRegistry.describe("model.obj", "application/octet-stream", EntryKind.OTHER)
        assertEquals(PreviewFamily.MODEL_3D, value.family)
        assertEquals(PreviewDepth.RENDERED, value.depth)
        assertEquals("mesh-wireframe", value.rendererId)
    }

    @Test fun stlAndPlyGetRenderedMeshPreview() {
        listOf("part.stl", "scan.ply", "shape.off").forEach { name ->
            val value = FileFormatRegistry.describe(name, "application/octet-stream", EntryKind.OTHER)
            assertEquals(PreviewFamily.MODEL_3D, value.family)
            assertEquals("mesh-wireframe", value.rendererId)
        }
    }

    @Test fun dxfGetsRenderedCadPreview() {
        val value = FileFormatRegistry.describe("drawing.dxf", "application/dxf", EntryKind.OTHER)
        assertEquals(PreviewFamily.CAD_2D, value.family)
        assertEquals(PreviewDepth.RENDERED, value.depth)
        assertEquals("dxf", value.rendererId)
    }

    @Test fun dwgGetsSafeInspectionInsteadOfFakeRendering() {
        val value = FileFormatRegistry.describe("drawing.dwg", "application/acad", EntryKind.OTHER)
        assertEquals(PreviewFamily.CAD_2D, value.family)
        assertEquals(PreviewDepth.INSPECTED, value.depth)
        assertEquals(null, value.rendererId)
        assertNotNull(value.notes)
    }

    @Test fun officeAndScientificFamiliesAreRecognized() {
        assertEquals(
            PreviewFamily.OFFICE,
            FileFormatRegistry.describe("report.docx", "application/octet-stream", EntryKind.OTHER).family,
        )
        assertEquals(
            PreviewFamily.SCIENTIFIC,
            FileFormatRegistry.describe("volume.nii.gz", "application/octet-stream", EntryKind.OTHER).family,
        )
    }

    @Test fun unknownExtensionAlwaysGetsBinaryInspector() {
        val value = FileFormatRegistry.describe("mystery.thing-that-does-not-exist", "application/octet-stream", EntryKind.OTHER)
        assertEquals(PreviewFamily.BINARY, value.family)
        assertEquals(PreviewDepth.INSPECTED, value.depth)
        assertEquals("binary", value.rendererId)
    }

    @Test fun figAndJamAreRecognizedAsDesignDocuments() {
        listOf("board.fig", "diagram.jam").forEach { name ->
            val value = FileFormatRegistry.describe(name, "application/octet-stream", EntryKind.OTHER)
            assertEquals(PreviewFamily.DESIGN, value.family)
            assertEquals(PreviewDepth.STRUCTURED, value.depth)
            assertEquals("Design document", value.label)
            assertEquals("design", value.rendererId)
        }
    }

    @Test fun designExtensionIsCaseInsensitive() {
        val value = FileFormatRegistry.describe("Board.FIG", "application/octet-stream", EntryKind.OTHER)
        assertEquals(PreviewFamily.DESIGN, value.family)
    }

    @Test fun rarGetsAnHonestUnsupportedNoteRatherThanSilentFailure() {
        val value = FileFormatRegistry.describe("archive.rar", "application/vnd.rar", EntryKind.OTHER)
        assertEquals(PreviewFamily.ARCHIVE, value.family)
        assertEquals("RAR listing is not supported.", value.notes)
    }

    @Test fun otherArchivesCarryNoSpuriousNote() {
        val value = FileFormatRegistry.describe("archive.zip", "application/zip", EntryKind.OTHER)
        assertEquals(PreviewFamily.ARCHIVE, value.family)
        assertEquals(null, value.notes)
    }

    @Test fun compoundArchiveExtensionsAreUnaffectedByTheDesignFamily() {
        listOf(
            "volume.nii.gz" to PreviewFamily.SCIENTIFIC,
            "backup.tar.gz" to PreviewFamily.ARCHIVE,
            "backup.tar.bz2" to PreviewFamily.ARCHIVE,
            "backup.tar.xz" to PreviewFamily.ARCHIVE,
            "backup.tar.zst" to PreviewFamily.ARCHIVE,
        ).forEach { (name, family) ->
            val value = FileFormatRegistry.describe(name, "application/octet-stream", EntryKind.OTHER)
            assertEquals("$name should resolve to $family", family, value.family)
        }
    }
}
