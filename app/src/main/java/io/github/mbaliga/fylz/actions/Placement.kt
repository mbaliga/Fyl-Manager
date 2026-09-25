package io.github.mbaliga.fylz.actions

/**
 * Where an action can be invoked from. `Toolbar`, `Menu` and four `RoomId`s are extensions of the
 * addendum's own placement list (design §2.2): two bars, three distinct menus and four rooms
 * exist today, not the one "toolbar"/"context menu" the addendum's own sketch names.
 */
sealed interface Placement {
    data class SelectionBar(val order: Int) : Placement
    data class Toolbar(val bar: Bar, val order: Int) : Placement
    data class Menu(val menu: MenuId, val order: Int) : Placement
    data class Room(val room: RoomId, val order: Int) : Placement
    data class ContextMenu(val group: String, val order: Int) : Placement
    data object CommandPalette : Placement
    data class Shortcut(val chord: KeyChord) : Placement
    data class Gesture(val gesture: GestureId, val targetWhen: ((ActionTarget) -> Boolean)? = null) : Placement
    data object QuickSettingsTile : Placement
    data object HomeCard : Placement
}
