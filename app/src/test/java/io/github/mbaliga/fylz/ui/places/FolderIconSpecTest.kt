package io.github.mbaliga.fylz.ui.places

import io.github.mbaliga.fylz.ui.places.FolderIconSpec.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderIconSpecTest {

    private fun assertPose(expected: FolderIconSpec.Pose, actual: FolderIconSpec.Pose) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
        assertEquals(expected.rotationDeg, actual.rotationDeg, 0.001f)
    }

    @Test fun cardCountIsZeroToThree() {
        assertEquals(0, FolderIconSpec.cardCount(0))
        assertEquals(0, FolderIconSpec.cardCount(-4))
        assertEquals(2, FolderIconSpec.cardCount(2))
        assertEquals(3, FolderIconSpec.cardCount(900))
    }

    @Test fun slotsDrawBottomToTopWithNewestOnTop() {
        assertEquals(emptyList<Slot>(), FolderIconSpec.slots(0))
        assertEquals(listOf(Slot.A), FolderIconSpec.slots(1))
        assertEquals(listOf(Slot.B, Slot.A), FolderIconSpec.slots(2))
        assertEquals(listOf(Slot.C, Slot.B, Slot.A), FolderIconSpec.slots(3))
    }

    @Test fun restPosesMatchTheHandoffSvgs() {
        // folder-light-3.svg: A (28, 19.3379, -15°), B (37, 21.3379, -3°), C (74, 23.3379, 10°).
        val a = FolderIconSpec.pose(Slot.A, 3, dark = false, t = 0f)
        val b = FolderIconSpec.pose(Slot.B, 3, dark = false, t = 0f)
        val c = FolderIconSpec.pose(Slot.C, 3, dark = false, t = 0f)
        assertPose(FolderIconSpec.Pose(28f, 19.3379f, -15f), a)
        assertPose(FolderIconSpec.Pose(37f, 21.3379f, -3f), b)
        assertPose(FolderIconSpec.Pose(74f, 23.3379f, 10f), c)
        // folder-dark-1.svg: the lone card rests at (4, 33.3379).
        assertPose(FolderIconSpec.Pose(4f, 33.3379f, -15f), FolderIconSpec.pose(Slot.A, 1, dark = true, t = 0f))
    }

    @Test fun fannedPosesMatchTheHandoffHoverValues() {
        // folder-light-3.svg hover: A (22, 7.3379, -21°), B (39, 13.3379, 3°), C (82, 17.3379, 18°).
        assertPose(FolderIconSpec.Pose(22f, 7.3379f, -21f), FolderIconSpec.pose(Slot.A, 3, false, 1f))
        assertPose(FolderIconSpec.Pose(39f, 13.3379f, 3f), FolderIconSpec.pose(Slot.B, 3, false, 1f))
        assertPose(FolderIconSpec.Pose(82f, 17.3379f, 18f), FolderIconSpec.pose(Slot.C, 3, false, 1f))
        // folder-light-1.svg hover: a lone card goes to (25, 9.3379, -18°).
        assertPose(FolderIconSpec.Pose(25f, 9.3379f, -18f), FolderIconSpec.pose(Slot.A, 1, false, 1f))
    }

    @Test fun halfwayIsHalfwayAndOvershootIsBounded() {
        val half = FolderIconSpec.pose(Slot.C, 3, false, 0.5f)
        assertEquals(78f, half.x, 0.0001f)
        assertEquals(20.3379f, half.y, 0.0001f)
        val wild = FolderIconSpec.pose(Slot.C, 3, false, 50f)
        assertEquals(74f + 8f * 1.2f, wild.x, 0.0001f)
    }

    @Test fun darkAndLightPlaceTheCardsDifferently() {
        assertEquals(true, FolderIconSpec.DARK.cardOrigin != FolderIconSpec.LIGHT.cardOrigin)
        assertEquals(0.8f, FolderIconSpec.DARK.frontAlpha, 0f)
        assertEquals(0.85f, FolderIconSpec.LIGHT.frontAlpha, 0f)
    }
}
