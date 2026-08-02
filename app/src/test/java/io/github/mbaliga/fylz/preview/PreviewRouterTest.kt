package io.github.mbaliga.fylz.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewRouterTest {
    @Test
    fun routesSvgAndSvgzInApp() {
        assertEquals(PreviewRoute.SVG, PreviewRouter.decide("diagram.svg", "image/svg+xml").route)
        assertEquals(PreviewRoute.SVG, PreviewRouter.decide("diagram.svgz", "application/octet-stream").route)
    }

    @Test
    fun signatureWinsForPdfAndGif() {
        assertEquals(
            PreviewRoute.PDF,
            PreviewRouter.decide("unknown.bin", null, "%PDF".encodeToByteArray()).route,
        )
        assertEquals(
            PreviewRoute.ANIMATED_IMAGE,
            PreviewRouter.decide("unknown.bin", null, "GIF89a".encodeToByteArray()).route,
        )
    }

    @Test
    fun unknownFilesUseInspectableFallback() {
        assertEquals(
            PreviewRoute.HEX_AND_METADATA,
            PreviewRouter.decide("payload.bin", "application/octet-stream").route,
        )
    }

    @Test
    fun agentArtifactsRouteAsStructuredText() {
        assertEquals(
            PreviewRoute.STRUCTURED_TEXT,
            PreviewRouter.decide("AGENTS.instructions", "application/octet-stream").route,
        )
    }
}
