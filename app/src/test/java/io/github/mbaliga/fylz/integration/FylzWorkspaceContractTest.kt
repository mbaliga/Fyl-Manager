package io.github.mbaliga.fylz.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FylzWorkspaceContractTest {

    @Test
    fun handleKeepsTreeUriOpaque() {
        val handle = FylzWorkspaceHandle(
            treeUri = "content://provider/tree/primary%3AProjects",
            displayName = "Projects",
        )

        assertEquals(FylzWorkspaceContract.CONTRACT_VERSION, handle.contractVersion)
        assertEquals("content://provider/tree/primary%3AProjects", handle.treeUri)
    }

    @Test
    fun rejectsFilesystemPathsAsWorkspaceHandles() {
        assertThrows(IllegalArgumentException::class.java) {
            FylzWorkspaceHandle(treeUri = "/storage/emulated/0/Projects")
        }
    }
}
