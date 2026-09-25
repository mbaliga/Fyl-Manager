package io.github.mbaliga.fylz.integration

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FylzOperationContractTest {
    @Test fun acceptsOpaqueStagingCapabilities() {
        val request = FylzExternalOperationRequest(
            requestId = "request-1",
            kind = FylzExternalOperationKind.STAGE_PACKAGE,
            sourceUri = Uri.parse("content://downloads/document/payload"),
            destinationTreeUri = Uri.parse("content://storage/tree/kindle"),
            destinationRelativePath = "documents/payload.bin",
            expectedSha256 = "a".repeat(64),
        )
        assertEquals("request-1", request.requestId)
    }

    @Test fun rejectsFilesystemPathAndTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            FylzExternalOperationRequest(
                requestId = "request-1",
                kind = FylzExternalOperationKind.STAGE_PACKAGE,
                sourceUri = Uri.parse("file:///sdcard/payload"),
                destinationTreeUri = Uri.parse("content://storage/tree/kindle"),
                destinationRelativePath = "../payload.bin",
                expectedSha256 = "a".repeat(64),
            )
        }
    }
}

