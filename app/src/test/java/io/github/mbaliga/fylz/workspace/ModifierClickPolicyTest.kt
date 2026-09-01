package io.github.mbaliga.fylz.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class ModifierClickPolicyTest {

    @Test
    fun `no modifiers means open`() {
        assertEquals(
            ModifierClick.Open,
            ModifierClickPolicy.decide(HeldModifiers.None, anchorIndex = 2, clickedIndex = 5),
        )
    }

    @Test
    fun `ctrl click toggles and meta click toggles identically`() {
        assertEquals(
            ModifierClick.ToggleSelection,
            ModifierClickPolicy.decide(HeldModifiers(ctrl = true), anchorIndex = null, clickedIndex = 3),
        )
        assertEquals(
            ModifierClick.ToggleSelection,
            ModifierClickPolicy.decide(HeldModifiers(meta = true), anchorIndex = null, clickedIndex = 3),
        )
    }

    @Test
    fun `shift click with an anchor selects the inclusive span either direction`() {
        assertEquals(
            ModifierClick.SelectRange(2, 7),
            ModifierClickPolicy.decide(HeldModifiers(shift = true), anchorIndex = 2, clickedIndex = 7),
        )
        assertEquals(
            ModifierClick.SelectRange(2, 7),
            ModifierClickPolicy.decide(HeldModifiers(shift = true), anchorIndex = 7, clickedIndex = 2),
        )
    }

    @Test
    fun `shift click with no anchor degrades to a toggle that plants one`() {
        assertEquals(
            ModifierClick.ToggleSelection,
            ModifierClickPolicy.decide(HeldModifiers(shift = true), anchorIndex = null, clickedIndex = 4),
        )
    }

    @Test
    fun `shift outranks ctrl and ctrl outranks alt`() {
        assertEquals(
            ModifierClick.SelectRange(1, 4),
            ModifierClickPolicy.decide(
                HeldModifiers(ctrl = true, shift = true, alt = true),
                anchorIndex = 1,
                clickedIndex = 4,
            ),
        )
        assertEquals(
            ModifierClick.ToggleSelection,
            ModifierClickPolicy.decide(HeldModifiers(ctrl = true, alt = true), anchorIndex = null, clickedIndex = 4),
        )
    }

    @Test
    fun `alt click opens externally`() {
        assertEquals(
            ModifierClick.OpenExternal,
            ModifierClickPolicy.decide(HeldModifiers(alt = true), anchorIndex = null, clickedIndex = 4),
        )
    }

    @Test
    fun `a click outside the visible list is always a plain open`() {
        assertEquals(
            ModifierClick.Open,
            ModifierClickPolicy.decide(HeldModifiers(ctrl = true, shift = true), anchorIndex = 2, clickedIndex = -1),
        )
    }

    @Test
    fun `range at a single index is legal`() {
        assertEquals(
            ModifierClick.SelectRange(3, 3),
            ModifierClickPolicy.decide(HeldModifiers(shift = true), anchorIndex = 3, clickedIndex = 3),
        )
    }
}
