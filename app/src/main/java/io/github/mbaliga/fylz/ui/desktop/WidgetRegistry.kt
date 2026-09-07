package io.github.mbaliga.fylz.ui.desktop

import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.desktop.DesktopItemSize
import io.github.mbaliga.fylz.desktop.DesktopWidgetType

/**
 * Static metadata for one [DesktopWidgetType]: what the "Add widget" gallery in [DesktopScreen]
 * shows for it ([displayNameRes]), what footprint a freshly added instance gets
 * ([defaultSize]), which sizes the user is allowed to resize it to ([allowedSizes], always
 * containing [defaultSize]), and how tall its card renders ([height]).
 *
 * [height] is the single source of truth for a widget's own content-fit footprint -- replacing the
 * old blanket 152/216/360-by-[DesktopItemSize] table [io.github.mbaliga.fylz.ui.desktop.desktopWidgetSize]
 * used to apply to every widget alike, regardless of what it actually drew. That blanket table
 * clipped tall content (Large files' five rows, Shelf's CTA) and wasted a LARGE card's worth of
 * space on a short one (Deleted files' single count row) -- [height] is tuned per TYPE instead, once,
 * here, so a card's footprint always matches what it draws. Width still comes from [defaultSize]/
 * [DesktopItemSize] via [io.github.mbaliga.fylz.desktop.DesktopPolicy.widgetWidthFraction] -- a
 * widget's WIDTH genuinely is shared across every type of its size class (all compact cards sit in
 * the same column), which is why that half of the old table was never the problem.
 *
 * **[height] is a FLOOR, and the numbers below are tighter than they look.** Applied as an exact
 * size it was a guillotine: [io.github.mbaliga.fylz.ui.overview.OverviewCardSurface] is a Material3
 * Surface (clips to its shape) around a top-anchored Column, so a card that outgrew its number lost
 * its LAST children -- the control at the bottom, every time -- with nothing on screen saying so.
 * [io.github.mbaliga.fylz.ui.desktop.WidgetTileContent] now applies it as `heightIn(min = ...)`.
 * Measured against what each renderer actually draws at fontScale 1.0 (titleMedium 24dp, bodyMedium
 * 20, bodySmall/labelSmall 16, headlineSmall 32; a TactileButton cap and a TactileIconKey are both
 * a fixed 48; the surface's own padding takes 32 off the declared figure -- SEARCH is a bare pill
 * with no card surface, so its two figures are the same number):
 *
 *   STORAGE        404 of 408   4dp spare
 *   RECYCLE_BIN    116 of 112   4dp OVER -- the retention sentence's second line
 *   QUICK_ACCESS   244 of 192   52dp OVER in its no-access state -- the "Grant access" cap
 *   PINNED         256 of 256   nothing spare once "+N more" shows
 *   RECENTS        232 of 232   nothing spare at four rows
 *   LARGE_FILES    328 of 328   nothing spare at five rows -- the "As of" line
 *   SEARCH          52 of  52   the pill's own natural height, by construction
 *   QUICK_ACTIONS   92 of  96   4dp spare
 *   TAGS            64 of  80   16dp spare
 *   SHELF          148 of 184   36dp spare
 *
 * The floor rescues eight of those ten. It does NOT rescue QUICK_ACCESS or SHELF, and saying so is
 * the point of listing them: both put their body in a `Modifier.weight(1f)` child, and a weighted
 * child is measured at EXACTLY its share, so those two Columns report the floor and squeeze inside
 * it however tall the card is allowed to be. SHELF has 36dp spare and does not care. QUICK_ACCESS's
 * no-access state is 52dp over and still loses its "Grant access" cap -- fixing that means either
 * the coordinated height change below or dropping the `weight` in
 * [io.github.mbaliga.fylz.ui.overview.QuickAccessCard], neither of which lives in this file.
 *
 * The six numbers that are short cannot be corrected here alone, which is why they still read as
 * they do. [io.github.mbaliga.fylz.desktop.DesktopPolicy.defaultSeed] lays its two columns out
 * against these exact constants by hand, `DesktopPolicyTest` asserts every seeded gutter is EXACTLY
 * 16dp computed from them, and `WORLD_MIN_HEIGHT_DP` is asserted to be the seed's own lowest edge
 * plus its bottom margin -- so raising a seeded widget's height without moving every y below it in
 * the same commit both fails those tests and overlaps the very cards it was meant to fix. Taking
 * "measured + 32dp of surface padding + ~24dp of headroom, rounded up to `VERTICAL_QUANTUM_DP`":
 * STORAGE 440 -> 472, RECYCLE_BIN 144 -> 176, QUICK_ACCESS 224 -> 304, PINNED 288 -> 312,
 * RECENTS 264 -> 288, LARGE_FILES 360 -> 384. That is one coordinated change to this file AND
 * `DesktopPolicy`'s seed and world floor -- not a change to this file.
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
    val height: Dp,
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
            // Header(24, titleMedium's own line height -- the old note said 22) + spacer(12) +
            // bar(12) + spacer(12) + legend(all six StorageKind rows, 6*16 + 5*8 gutters = 136) +
            // spacer(8) + as-of row (48, the Rescan key's own touch target) + spacer(4) +
            // disclaimer (2 lines at 16dp/line = 32) + spacer(12) + two STACKED CTAs (the fidelity
            // pass stacks them full-width instead of squeezing them side by side; TactileButton's
            // cap is a fixed 48dp, so 48 + 8 gap + 48 = 104, not the 88 the old note claimed for
            // 40dp OutlinedButtons that were replaced) = 404 inside the 408dp this leaves after
            // OverviewCardSurface's own 32dp of padding. Four dp, not the headroom the old sum
            // read as -- see this file's own KDoc for why it cannot be corrected here alone.
            height = 440.dp,
        )
        DesktopWidgetType.QUICK_ACCESS -> WidgetRegistration(
            type = DesktopWidgetType.QUICK_ACCESS,
            displayNameRes = R.string.desktop_widget_quick_access,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
            // Header row (its overflow button is now a real 48dp touch target) + thumbnail row +
            // item count = 132 of 192, comfortable. Its NO-ACCESS state is not: EmptyFolderState's
            // icon, title, four-line body and "Grant access" cap come to 188, which with the
            // header is 244 -- the cap is the part that used to fall off the bottom.
            height = 224.dp,
        )
        DesktopWidgetType.RECYCLE_BIN -> WidgetRegistration(
            type = DesktopWidgetType.RECYCLE_BIN,
            displayNameRes = R.string.desktop_widget_recycle_bin,
            // Was MEDIUM here while io.github.mbaliga.fylz.desktop.DesktopPolicy.defaultSeed
            // placed it LARGE -- two different "default" sizes for the one type. LARGE wins: the
            // reference overview always gave Deleted files the full-width treatment; what was
            // actually wrong was the HEIGHT a LARGE card got (the blanket 360dp for a one-row
            // count chip), fixed by `height` below, not by shrinking the card's width class.
            defaultSize = DesktopItemSize.LARGE,
            allowedSizes = setOf(DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
            // Header row + one count-chip row -- short, honest content, not the old 360dp blanket.
            // Header 48 (the "See Files" cap) + 12 + 56 (the count chip: headlineSmall 32 plus its
            // 12dp padding either side) = 116 against 112, so the retention sentence beside the
            // chip loses its second line wherever it wraps.
            height = 144.dp,
        )
        DesktopWidgetType.TAGS -> WidgetRegistration(
            type = DesktopWidgetType.TAGS,
            displayNameRes = R.string.desktop_widget_tags,
            defaultSize = DesktopItemSize.SMALL,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
            // Header + one scrollable chip row = 64 of 80, and 104 of 112 even at fontScale 1.3.
            // Left at 112 deliberately, against the obvious reading of "Tags is a tiny thing":
            // this card is not short of room, it is short of a DESTINATION -- every route out of a
            // chip ends in an AlertDialog, and there is no tags place in
            // [io.github.mbaliga.fylz.ui.landing.HomeMode] for one to lead to. A taller card here
            // would buy whitespace, not reach.
            height = 112.dp,
        )
        DesktopWidgetType.PINNED -> WidgetRegistration(
            type = DesktopWidgetType.PINNED,
            displayNameRes = R.string.desktop_widget_pinned,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
            // Header + up to 4 rows at the new >=44dp touch-target row height + the "+N more" line.
            height = 288.dp,
        )
        DesktopWidgetType.SHELF -> WidgetRegistration(
            type = DesktopWidgetType.SHELF,
            displayNameRes = R.string.desktop_widget_shelf,
            defaultSize = DesktopItemSize.SMALL,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM),
            // Header + up to 3 preview rows + the "Open shelf" CTA -- was clipping that CTA at the
            // old blanket 152dp.
            height = 216.dp,
        )
        DesktopWidgetType.RECENTS -> WidgetRegistration(
            type = DesktopWidgetType.RECENTS,
            displayNameRes = R.string.desktop_widget_recents,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
            // Header + up to 4 rows at the new >=44dp touch-target row height.
            height = 264.dp,
        )
        DesktopWidgetType.SEARCH -> WidgetRegistration(
            type = DesktopWidgetType.SEARCH,
            displayNameRes = R.string.desktop_widget_search,
            defaultSize = DesktopItemSize.SMALL,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM),
            // A PILL, not a card -- its own natural height (14dp vertical padding either side of a
            // 24dp icon/20dp text line), not a card-sized box it used to be stretched into and then
            // erased by the identical-colour backing plate this fidelity pass removes.
            height = 52.dp,
        )
        DesktopWidgetType.QUICK_ACTIONS -> WidgetRegistration(
            type = DesktopWidgetType.QUICK_ACTIONS,
            displayNameRes = R.string.desktop_widget_quick_actions,
            defaultSize = DesktopItemSize.SMALL,
            allowedSizes = setOf(DesktopItemSize.SMALL, DesktopItemSize.MEDIUM),
            // Header + one row of four 40dp action discs.
            height = 128.dp,
        )
        DesktopWidgetType.LARGE_FILES -> WidgetRegistration(
            type = DesktopWidgetType.LARGE_FILES,
            displayNameRes = R.string.desktop_widget_large_files,
            defaultSize = DesktopItemSize.MEDIUM,
            allowedSizes = setOf(DesktopItemSize.MEDIUM, DesktopItemSize.LARGE),
            // Header + up to 5 rows at the new >=44dp touch-target row height + the as-of line.
            height = 360.dp,
        )
    }
}
