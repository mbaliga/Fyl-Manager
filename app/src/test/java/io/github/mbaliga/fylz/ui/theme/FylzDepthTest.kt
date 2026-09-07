package io.github.mbaliga.fylz.ui.theme

import androidx.compose.ui.unit.dp
import dev.aarso.hyle.tokens.HyleTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FylzGeometry] is meant to be a thin, genuinely-consuming wrapper around
 * [HyleTokens.Dimension]'s radius tokens, not a second hardcoded copy of the same four numbers --
 * this pins that linkage so a future edit to either side gets caught instead of quietly drifting.
 * [Modifier.softShadow] and the two `@Composable` helpers alongside it need a device/Compose test
 * host to exercise meaningfully, so they're left to instrumented/manual verification; this file
 * covers the one piece of pure logic in FylzDepth.kt.
 */
class FylzDepthTest {

    @Test
    fun `radii read straight off HyleTokens, not a re-declared copy`() {
        assertEquals(HyleTokens.Dimension.radiusSm.dp, FylzGeometry.RadiusSm)
        assertEquals(HyleTokens.Dimension.radiusMd.dp, FylzGeometry.RadiusMd)
        assertEquals(HyleTokens.Dimension.radiusLg.dp, FylzGeometry.RadiusLg)
        assertEquals(HyleTokens.Dimension.radiusXl.dp, FylzGeometry.RadiusXl)
    }

    @Test
    fun `radii land on the Hyle vocabulary -- 4, 8, 12, 16dp, capped at 16`() {
        assertEquals(4.dp, FylzGeometry.RadiusSm)
        assertEquals(8.dp, FylzGeometry.RadiusMd)
        assertEquals(12.dp, FylzGeometry.RadiusLg)
        assertEquals(16.dp, FylzGeometry.RadiusXl)
    }

    @Test
    fun `radii strictly increase -- sm less than md less than lg less than xl`() {
        assertTrue(FylzGeometry.RadiusSm < FylzGeometry.RadiusMd)
        assertTrue(FylzGeometry.RadiusMd < FylzGeometry.RadiusLg)
        assertTrue(FylzGeometry.RadiusLg < FylzGeometry.RadiusXl)
    }
}
