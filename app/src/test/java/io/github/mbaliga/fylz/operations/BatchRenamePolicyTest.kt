package io.github.mbaliga.fylz.operations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRenamePolicyTest {
    @Test
    fun `valid names are accepted`() {
        assertTrue(BatchRenamePolicy.isValidName("Document 01.txt"))
        assertTrue(BatchRenamePolicy.isValidName(".env"))
        assertTrue(BatchRenamePolicy.isValidName("photo-final.webp"))
    }

    @Test
    fun `unsafe names are rejected`() {
        assertFalse(BatchRenamePolicy.isValidName(""))
        assertFalse(BatchRenamePolicy.isValidName("   "))
        assertFalse(BatchRenamePolicy.isValidName("."))
        assertFalse(BatchRenamePolicy.isValidName(".."))
        assertFalse(BatchRenamePolicy.isValidName("folder/file.txt"))
        assertFalse(BatchRenamePolicy.isValidName("bad\u0000name"))
    }
}
