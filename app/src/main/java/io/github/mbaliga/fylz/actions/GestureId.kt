package io.github.mbaliga.fylz.actions

enum class GestureId {
    EDGE_LEFT, EDGE_RIGHT, EDGE_BOTTOM, SHAKE, ITEM_TAP, ITEM_LONG_PRESS, ITEM_DOUBLE_TAP,

    /** A two-finger pinch on the browse surface -- see `PinchInBehavior` for what it does. */
    PINCH_IN, PINCH_OUT,
}
