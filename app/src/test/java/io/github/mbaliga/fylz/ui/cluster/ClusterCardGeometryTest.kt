package io.github.mbaliga.fylz.ui.cluster

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dragged cluster makes three geometric promises, all of them about the one thing a code read
 * cannot check: whether you can see what you are carrying while your own thumb is on top of it.
 * Each is a number, so each is checkable here rather than left as a claim in a KDoc.
 *
 * These held once, in a render, on the day they were written. What this file is for is the day
 * someone tunes one constant -- shaves the lift to buy vertical room, or tightens the fan to make
 * the stack read as one object -- and quietly gives the occlusion back.
 */
class ClusterCardGeometryTest {

    /**
     * The card has to be wider than the thumb, or enlarging it bought nothing: a card narrower
     * than the contact patch is hidden whatever else is done to it.
     */
    @Test
    fun `the card in flight is wider than the thumb carrying it`() {
        assertTrue(
            "card is ${CARD_SIZE.value}dp across, no wider than the ${ThumbOcclusionDp.value}dp a thumb covers",
            CARD_SIZE > ThumbOcclusionDp,
        )
    }

    /**
     * The whole card, filename band included, has to clear the contact point -- not merely peek
     * past it. The label sits on the card's bottom edge, so "clears" means the bottom edge is
     * above the finger with a margin, not level with it.
     */
    @Test
    fun `the whole card sits above the contact point`() {
        val bottomEdgeAboveFinger = THUMB_LIFT - CARD_SIZE / 2
        assertTrue(
            "the card's bottom edge lands ${bottomEdgeAboveFinger.value}dp above the finger; " +
                "the filename band needs at least ${LABEL_CLEARANCE.value}dp",
            bottomEdgeAboveFinger >= LABEL_CLEARANCE,
        )
    }

    /**
     * A fan step small enough to hide behind the leader's own opaque face is a fan in name only --
     * five files and one file draw the same picture. This is the floor found by rendering: below
     * about 8dp on a 96dp card the stack stops being countable.
     */
    @Test
    fun `trailing cards peek far enough to be counted`() {
        listOf("x" to FAN_STEP_X, "y" to FAN_STEP_Y).forEach { (axis, step) ->
            assertTrue("fan step on $axis is ${step.value}dp, under the ${MIN_PEEK.value}dp floor", step >= MIN_PEEK)
        }
    }

    private companion object {
        /** Roughly the filename band's own height, so it is not half under a fingertip. */
        val LABEL_CLEARANCE = 10.dp

        /** The narrowest strip of a trailing card that still reads as a separate card. */
        val MIN_PEEK = 8.dp
    }
}
