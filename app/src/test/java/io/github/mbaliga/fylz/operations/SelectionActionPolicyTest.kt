package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.core.model.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionPolicyTest {

    @Test
    fun `an empty selection offers nothing`() {
        val actions = SelectionActionPolicy.evaluate(emptyList())

        assertFalse(actions.any)
        assertEquals(0, actions.count)
        assertFalse(actions.copy)
        assertFalse(actions.move)
        assertFalse(actions.recycle)
        assertFalse(actions.share)
    }

    @Test
    fun `one file renames but does not batch rename`() {
        val actions = SelectionActionPolicy.evaluate(listOf(EntryKind.TEXT))

        assertTrue(actions.rename)
        assertFalse(actions.batchRename)
    }

    @Test
    fun `two files batch rename but do not rename`() {
        val actions = SelectionActionPolicy.evaluate(listOf(EntryKind.TEXT, EntryKind.IMAGE))

        assertFalse(actions.rename)
        assertTrue(actions.batchRename)
    }

    @Test
    fun `extract needs exactly one archive`() {
        assertTrue(SelectionActionPolicy.evaluate(listOf(EntryKind.ARCHIVE)).extract)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.ARCHIVE, EntryKind.ARCHIVE)).extract)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.ARCHIVE, EntryKind.PDF)).extract)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.PDF)).extract)
    }

    @Test
    fun `pdf tools need every entry to be a pdf`() {
        assertTrue(SelectionActionPolicy.evaluate(listOf(EntryKind.PDF, EntryKind.PDF)).pdfTools)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.PDF, EntryKind.IMAGE)).pdfTools)
    }

    @Test
    fun `annotate needs exactly one image`() {
        assertTrue(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE)).annotate)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE, EntryKind.IMAGE)).annotate)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE, EntryKind.PDF)).annotate)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.PDF)).annotate)
    }

    @Test
    fun `convert image needs exactly one image`() {
        assertTrue(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE)).convertImage)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE, EntryKind.IMAGE)).convertImage)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.PDF)).convertImage)
    }

    @Test
    fun `images to pdf needs every entry to be an image, but any count`() {
        assertTrue(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE)).imagesToPdf)
        assertTrue(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE, EntryKind.IMAGE, EntryKind.IMAGE)).imagesToPdf)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE, EntryKind.PDF)).imagesToPdf)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.PDF)).imagesToPdf)
    }

    /**
     * A folder URI handed to `ACTION_SEND` produces a share sheet that delivers nothing, which is
     * worse than an action that is not offered.
     */
    @Test
    fun `share refuses a selection containing a folder`() {
        assertTrue(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE, EntryKind.VIDEO)).share)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.IMAGE, EntryKind.DIRECTORY)).share)
        assertFalse(SelectionActionPolicy.evaluate(listOf(EntryKind.DIRECTORY)).share)
    }

    @Test
    fun `folders still copy move recycle tag and archive`() {
        val actions = SelectionActionPolicy.evaluate(listOf(EntryKind.DIRECTORY))

        assertTrue(actions.copy)
        assertTrue(actions.move)
        assertTrue(actions.recycle)
        assertTrue(actions.tag)
        assertTrue(actions.archive)
    }
}
