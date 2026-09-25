package io.github.mbaliga.fylz.actions

/** The two persistent icon rows -- distinct today, not one "toolbar" (design §2.2). */
enum class Bar { TOP_APP_BAR, BROWSER_ROW }

/** Every `DropdownMenu`/menu-shaped surface in the app today. */
enum class MenuId { OVERFLOW, SORT, ARCHIVE_TOOLS }

/** The four rooms (design §2.2; §C1 names room items as registry lookups). */
enum class RoomId { LOCATIONS, LIBRARY_RAIL, TOOLS, RECOVERY }
