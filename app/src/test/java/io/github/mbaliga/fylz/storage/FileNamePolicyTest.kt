package io.github.mbaliga.fylz.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileNamePolicyTest {
    @Test
    fun trimsAndReturnsValidNames() {
        assertEquals("notes.md", FileNamePolicy.validate("  notes.md  "))
    }

    @Test
    fun rejectsBlankReservedAndPathLikeNames() {
        assertThrows(IllegalArgumentException::class.java) { FileNamePolicy.validate("  ") }
        assertThrows(IllegalArgumentException::class.java) { FileNamePolicy.validate("..") }
        assertThrows(IllegalArgumentException::class.java) { FileNamePolicy.validate("a/b") }
        assertThrows(IllegalArgumentException::class.java) { FileNamePolicy.validate("bad\u0000name") }
    }
}
