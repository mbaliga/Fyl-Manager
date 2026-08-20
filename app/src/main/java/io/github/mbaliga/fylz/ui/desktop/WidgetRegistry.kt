package io.github.mbaliga.fylz.ui.desktop

import androidx.annotation.StringRes
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.desktop.DesktopItemSize
import io.github.mbaliga.fylz.desktop.DesktopWidgetType

/**
 * Static metadata for one [DesktopWidgetType]: what the "Add widget" gallery in [DesktopScreen]
 * shows for it ([displayNameRes]), what footprint a freshly added instance gets
 * ([defaultSize]), and which sizes the user is allowed to resize it to ([allowedSizes], always
 * containing [defaultSize]).
 *
 * The renderer entry point per type is [WidgetRenderers]' own `when (item.type)` dispatch, not a
 * function reference stored here -- [WidgetRegistry.of] below is itself an exhaustive `when`, so a
 * [DesktopWidgetType] added to the enum without a matching branch here (or in [WidgetRenderers]'
 * own dispatch) fails the build rather than falling through to a made-up default at runtime;
 * duplicating a stored lookup for the same guarantee would only be redundant.
 */
data class WidgetRegistration(
    val type: DesktopWidgetType,
    @param:StringRes val displayNameRes: Int,
    val defaultSize: DesktopItemSize,
    val allowedSizes: Set<DesktopItemSize>,
)

object WidgetRegistry {

    /** Every widget type, in the order the "Add widget" gallery lists them -- matches
     *  [io.github.mbaliga.fylz.desktop.DesktopPolicy.defaultSeed]'s own ordering where the two
     *  overlap, with the two types that seed never places
     *  ([DesktopWidgetType.SEARCH], [DesktopWidgetType.LARGE_FILES]) appended at the end. */
    val entries: List<WidgetRegistration> = DesktopWidgetType.entries.map(::of)

    /** Exhaustive by construction: every [DesktopWidgetType] branch below, no `else`, so a widget
     *  type added to that enum without a matching branch here fails the build. */
    fun of(type: DesktopWidgetType): WidgetRegistration = when (type) {
        DesktopWidgetType.STORAGE -> WidgetRegistration(
            type = DesktopWidgetType.STORAGE,
            displayNameRes = R.string.desktop_widget_storage,
            defaultSize = DesktopItemSize.LARGE,
            allowedSizes = setOf(DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
        )
        DesktopWidgetType.QUICK_ACCESS -> WidgetRegistration(
            type = DesktopWidgetType.QUICK_ACCESS,
            displayNameRes = R.string.desktop_widget_quick_access,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
        )
        DesktopWidgetType.RECYCLE_BIN -> WidgetRegistration(
            type = DesktopWidgetType.RECYCLE_BIN,
            displayNameRes = R.string.desktop_widget_recycle_bin,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
        )
        DesktopWidgetType.TAGS -> WidgetRegistration(
            type = DesktopWidgetType.TAGS,
            displayNameRes = R.string.desktop_widget_tags,
            defaultSize = DesktopItemSize.SMALL,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
        )
        DesktopWidgetType.PINNED -> WidgetRegistration(
            type = DesktopWidgetType.PINNED,
            displayNameRes = R.string.desktop_widget_pinned,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
        )
        DesktopWidgetType.SHELF -> WidgetRegistration(
            type = DesktopWidgetType.SHELF,
            displayNameRes = R.string.desktop_widget_shelf,
            defaultSize = DesktopItemSize.SMALL,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM),
        )
        DesktopWidgetType.RECENTS -> WidgetRegistration(
            type = DesktopWidgetType.RECENTS,
            displayNameRes = R.string.desktop_widget_recents,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
        )
        DesktopWidgetType.SEARCH -> WidgetRegistration(
            type = DesktopWidgetType.SEARCH,
            displayNameRes = R.string.desktop_widget_search,
            defaultSize = DesktopItemSize.SMALL,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM),
        )
        DesktopWidgetType.QUICK_ACTIONS -> WidgetRegistration(
            type = DesktopWidgetType.QUICK_ACTIONS,
            displayNameRes = R.string.desktop_widget_quick_actions,
            defaultSize = DesktopItemSize.SMALL,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM),
        )
        DesktopWidgetType.LARGE_FILES -> WidgetRegistration(
            type = DesktopWidgetType.LARGE_FILES,
            displayNameRes = R.string.desktop_widget_large_files,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
        )
    }
}
