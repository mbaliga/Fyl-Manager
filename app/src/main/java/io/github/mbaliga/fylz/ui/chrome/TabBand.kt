package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.ui.cluster.TrashGlyph

/**
 * One open folder tab as the band draws it. Deliberately not
 * [io.github.mbaliga.fylz.model.FolderTab] itself -- the band only ever needs an id to compare
 * against [activeTabId] and a label to draw, and keeping its own tiny shape means this file has
 * no reason to depend on the browser's tab model or anything upstream of it.
 */
data class TabBandItem(val id: String, val label: String)

/** The visible height of one tab's own rectangle -- the folder-shape body, not the shadow spill
 *  above or below it, nor the plinth beneath. */
val TabStripHeight: Dp = 47.dp

/** How much vertical room the tab band claims at the bottom of the browser, mirroring the job
 *  `CommandPillReservedHeight` did for the floating pill -- small clearance beyond the tab body
 *  itself so a listing's last row doesn't run directly under the upward shadow gradient. */
val TabBandHeight: Dp = TabStripHeight + 10.dp

/** How far the shadow gradient spills past a tab's edge -- the export's 4px offset plus 4px blur,
 *  approximated as one soft band since Compose gradients don't blur. */
private val TabShadowSpan: Dp = 8.dp

/** Consecutive folder tabs overlap by this much -- the amount the export's x-offsets differ from
 *  each tab's own width, which is what produces the stacked-folder-tab read. Preserved as a fixed
 *  overlap rather than a fraction of tab width so it reads the same at any label length. */
private val TabOverlap: Dp = 26.dp

/** The trash tab's overlap with its left neighbour is wider than a folder tab's own cascade --
 *  the export pins it near the trailing edge (it ends up overlapping Documents by roughly 45dp of
 *  a 440dp-wide frame) rather than treating it as one more tab in the sequence. This reproduces
 *  that pinned read without hard-coding the export's specific frame width. */
private val TrashOverlap: Dp = 40.dp

private val TabShadowColor = Color.Black.copy(alpha = 0.25f)

/**
 * The bottom chrome: real overlapping folder tabs on a black plinth that bleeds to the screen's
 * physical bottom, trash pinned at the trailing end.
 *
 * **Gesture contract -- claims no vertical axis, on purpose.** The shell's own 56dp bottom edge
 * drag band (`SpatialShell.EDGE_DP`) fully contains this band's height and runs its edge-drag
 * detector on [androidx.compose.ui.input.pointer.PointerEventPass.Initial], ahead of anything a
 * child composes here. A vertical drag starting on a tab is meant to open the actions room the
 * same way it would from bare listing content -- that is the shell's own bottom-edge gesture, not
 * this band's. What this band claims is a plain tap (open/select), a long-press (close) and
 * horizontal scroll (more tabs than fit): [combinedClickable] and
 * [androidx.compose.foundation.horizontalScroll] only ever recognise those axes, so a vertical
 * drag starting here is never consumed by this band and reaches the shell's Initial-pass detector
 * exactly as if no tab were underneath it. Do not add a `pointerInput` here that consumes drag
 * deltas on any axis -- that is what would break the shell's edge gesture.
 *
 * **Shadow direction.** Compose's own `shadowElevation` only ever casts downward, so the active
 * tab's "sits in front, shadow above it" read and the trash tab's matching read (it is always
 * drawn front-most, at the end of the row) are both painted as a manual gradient overlay rather
 * than elevation -- see [drawTabShadow]. Inactive tabs get the same treatment mirrored below
 * their own bottom edge.
 *
 * @param tabs every open tab, drawn overlapping in a horizontally scrollable strip.
 * @param activeTabId which of [tabs] draws front-most, white (or frosted), with the upward shadow.
 * @param onTabSelected called with the id of the tapped tab.
 * @param onTabClosed called with the tab whose body was long-pressed.
 * @param onAddTab called from the strip's trailing "+".
 * @param onTrashTap called when the trash tab itself is tapped -- opens the bin. Dragging a
 *   cluster onto the trash tab's own screen position is a separate, drag-target concern the
 *   caller wires up outside this composable (see [trashModifier]); this callback is the tap route
 *   only, matching the tab strip's own tap-to-open pattern.
 * @param frosted true under the Fylz theme's translucent active tab (`rgba(255,255,255,0.2)` in
 *   the export); false draws the active tab as solid white. True backdrop blur of whatever sits
 *   behind the band is not attempted here -- it would need the listing's own content captured
 *   into a shared layer, which is beyond one self-contained composable -- so the frosted variant
 *   is translucency alone.
 * @param trashProximity 0 at rest, 1 with a dragged cluster over the trash tab -- forwarded
 *   straight to [TrashGlyph], the same reactive glyph the corner bulge used, so retiring that
 *   bulge in favour of this tab loses none of its "the can notices you" motion. Unused today:
 *   the drag layer draws its own can, at zIndex 20, directly over this tab, so a cue painted
 *   here would be behind it. See ClusterDragLayer's trash bulge.
 * @param trashModifier extra modifier chained onto the trash tab alone, for a caller that needs
 *   to read its screen position as a cluster-drag drop target.
 * @param onTabBounds each folder tab's measured position, in root coordinates, reported as it is
 *   laid out -- the same space a cluster drag reports the finger in, so a caller can hit-test a
 *   drop against the tabs themselves rather than against guessed geometry. The pending "+" chip
 *   reports too; it is the caller's job to know that its id names no open folder.
 * @param armedTabId the tab a dragged cluster is currently over AND would really land in, or
 *   null. A LAMBDA, not a value: it is read inside each chip, so a drag crossing the strip
 *   invalidates the chips rather than this whole band and its caller. The caller returns null
 *   for a drop that would do nothing (the folder already open, a folder into itself), which is
 *   what keeps an ineligible tab from lighting up.
 * @param canNavigateUp whether the active tab has an enclosing folder to leave TO -- gates the
 *   leading up-chevron the same way it gates the equivalent control everywhere else in the app.
 * @param onNavigateUp called when the up-chevron is tapped; a no-op caller with [canNavigateUp]
 *   left false draws no chevron at all rather than a disabled one with nothing to do.
 * @param selectionActive true while one or more entries are selected -- swaps the leading chip
 *   from the up-chevron to a red recycle button, since "go up" and "recycle the selection" never
 *   both make sense at once and the band has only the one leading slot.
 * @param onRecycleSelection called from that recycle button; the caller's job to move whatever is
 *   selected to the recycle bin, exactly as the actions menu's own recycle entry already does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabBand(
    tabs: List<TabBandItem>,
    activeTabId: String?,
    onTabSelected: (String) -> Unit,
    onTabClosed: (TabBandItem) -> Unit,
    onAddTab: () -> Unit,
    onTrashTap: () -> Unit,
    modifier: Modifier = Modifier,
    frosted: Boolean = false,
    trashProximity: Float = 0f,
    trashModifier: Modifier = Modifier,
    onTabBounds: (String, Rect) -> Unit = { _, _ -> },
    armedTabId: () -> String? = { null },
    canNavigateUp: Boolean = false,
    onNavigateUp: () -> Unit = {},
    selectionActive: Boolean = false,
    onRecycleSelection: () -> Unit = {},
) {
    val family = chromeFontFamily()
    Box(modifier.fillMaxWidth().background(ChromeInk)) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(TabStripHeight),
            // The tabs sub-Row below is capped at weight(1f, fill = false) so a wide tab strip
            // never pushes trash off-screen, but that cap does not make it CLAIM the full share
            // -- with few tabs open it measures to its own short content width, and without
            // SpaceBetween the unweighted trash chip would sit right after that width instead of
            // pinned to the band's own right edge.
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                // fill = false: this row claims at most what's left after the trash tab, never
                // more -- without the cap, a browsing session with enough open tabs to fill the
                // screen would push trash straight off the row instead of leaving it reachable.
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(-TabOverlap),
            ) {
                // The band's one leading slot: an up-chevron at rest, a red recycle button while
                // a selection is live, nothing when neither applies. Its own trailing padding
                // cancels the run's -TabOverlap spacing (meant for tab-over-tab stacking, not
                // this much smaller circular chip) and replaces it with a plain small gap instead
                // -- see [LeadingChipGap].
                val leadingChipModifier = Modifier.padding(end = TabOverlap + LeadingChipGap).zIndex(1f)
                if (selectionActive) {
                    RecycleChip(onClick = onRecycleSelection, modifier = leadingChipModifier)
                } else if (canNavigateUp) {
                    UpChip(onClick = onNavigateUp, modifier = leadingChipModifier)
                }
                tabs.forEach { tab ->
                    val isActive = tab.id == activeTabId
                    FolderTabChip(
                        label = tab.label,
                        front = isActive,
                        fill = if (isActive) {
                            if (frosted) ChromeOn.copy(alpha = 0.2f) else ChromeOn
                        } else {
                            ChromeInactive
                        },
                        textColor = if (isActive) ChromeInk else ChromeOn,
                        textShadow = if (isActive) {
                            Shadow(color = ChromeOn.copy(alpha = 0.25f), offset = Offset.Zero, blurRadius = 4f)
                        } else {
                            null
                        },
                        family = family,
                        onClick = { onTabSelected(tab.id) },
                        onLongClick = { onTabClosed(tab) },
                        armed = { armedTabId() == tab.id },
                        modifier = Modifier
                            .zIndex(if (isActive) 1f else 0f)
                            .onGloballyPositioned { onTabBounds(tab.id, it.boundsInRoot()) }
                            .semantics { selected = isActive },
                    )
                }
                AddTabButton(onAddTab)
            }
            TrashTabChip(
                proximity = trashProximity,
                onClick = onTrashTap,
                modifier = trashModifier.offset(x = -TrashOverlap).zIndex(1f),
            )
        }
    }
}

/**
 * One folder tab: shadow, clipped fill and label in a single layer stack, sized by its own label
 * rather than a literal export width -- a folder named longer than "Downloads" still needs
 * somewhere to put its letters. [drawTabShadow] is chained before [clip] specifically so it paints
 * in this node's own unclipped canvas space, using the same [DrawScope.size] the clip and
 * background resolve to -- one width, read once, instead of two siblings that would otherwise
 * have to be kept in sync by hand.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTabChip(
    label: String,
    front: Boolean,
    fill: Color,
    textColor: Color,
    textShadow: Shadow?,
    family: FontFamily,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    armed: () -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(TabStripHeight)
            .defaultMinSize(minWidth = 64.dp)
            .drawBehind { drawTabShadow(front) }
            .clip(FolderTabShape(mirrored = front))
            .background(fill)
            // The drop cue: nothing at rest, a pale lift over this tab's own fill while a
            // dragged cluster is over it and would really land here. Invoked in this chip so a
            // drag across the strip costs a redraw per chip, not a recomposition of the band --
            // see [TabBand]'s armedTabId. Only ever seen on an INACTIVE tab: the tab you are
            // already in is never an eligible destination, so this never washes white on white.
            .background(ChromeOn.copy(alpha = if (armed()) 0.28f else 0f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = "Open $label",
                onLongClickLabel = "Close $label",
            )
            // The slant eats into the right edge -- extra end padding keeps the label clear of
            // the diagonal instead of running under it.
            .padding(start = 14.dp, end = 14.dp + FolderTabSlant),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            style = tabLabelStyle(family, textColor, textShadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrashTabChip(
    proximity: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(TabStripHeight)
            .defaultMinSize(minWidth = 64.dp)
            .drawBehind { drawTabShadow(front = true) }
            .clip(FolderTabShape(mirrored = true))
            .background(ChromeDanger)
            .clickable(onClick = onClick, onClickLabel = "Open the trash")
            .padding(end = FolderTabSlant),
        contentAlignment = Alignment.Center,
    ) {
        // Glyph-only: pure #FF0000 fill under white text clears 4.0:1, not DESIGN.md's 4.5:1
        // text-contrast gate. The recycle-bin shape alone reads fine at this contrast; a label
        // would not.
        TrashGlyph(proximity = proximity, tint = ChromeOn, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun AddTabButton(onAddTab: () -> Unit) {
    Box(
        Modifier
            .width(44.dp)
            .height(TabStripHeight)
            .background(ChromeInactive)
            .clickable(onClick = onAddTab, onClickLabel = "Open a new tab"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = ChromeOn)
    }
}

/** Diameter of the band's one leading chip -- sized to the 48dp touch floor rather than
 *  [TabStripHeight] (47dp, a hairline under it): the one control here not already pinned to the
 *  strip's own body height by the Figma read, so nothing stops it clearing the floor outright. */
private val LeadingChipDiameter: Dp = 48.dp

/** The gap between the leading chip and the first tab -- see its call site for how this cancels
 *  the tab run's own -TabOverlap spacing instead of compounding with it. */
private val LeadingChipGap: Dp = 8.dp

/**
 * The default leading chip: a plain up-chevron to the folder the active tab's current location
 * sits inside, matching the same "go up" every other up-navigation control in the app already
 * offers -- [onClick] is the identical callback, not a second implementation of it.
 */
@Composable
private fun UpChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(LeadingChipDiameter)
            .clip(CircleShape)
            .background(ChromeInactive)
            .clickable(onClick = onClick, onClickLabel = stringResource(R.string.browser_parent_folder)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null, tint = ChromeOn)
    }
}

/**
 * The leading chip while a selection is live: the same red [ChromeDanger] and the same
 * [TrashGlyph] the pinned trash tab already draws, so this reads as "recycle" for the same reason
 * that tab does, rather than inventing a second glyph for one action. Distinct from that tab's own
 * tap-to-browse action -- this one recycles the SELECTION ([onClick]), not opens the bin.
 */
@Composable
private fun RecycleChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(LeadingChipDiameter)
            .clip(CircleShape)
            .background(ChromeDanger)
            .clickable(onClick = onClick, onClickLabel = "Move to Recycle Bin"),
        contentAlignment = Alignment.Center,
    ) {
        TrashGlyph(proximity = 0f, tint = ChromeOn, modifier = Modifier.size(24.dp))
    }
}

/**
 * Paints the directional shadow gradient just outside this node's own bounds: above the top edge
 * for a front tab (the export's `box-shadow 0 -4px 4px`), below the bottom edge for a back one
 * (`0 4px 4px`). [translate] shifts the drawing origin without touching layout, so this reads the
 * node's real resolved [DrawScope.size] and needs no width of its own to track.
 */
private fun DrawScope.drawTabShadow(front: Boolean) {
    val span = TabShadowSpan.toPx()
    val rectSize = Size(size.width, span)
    if (front) {
        translate(top = -span) {
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, TabShadowColor), 0f, span), size = rectSize)
        }
    } else {
        translate(top = size.height) {
            drawRect(brush = Brush.verticalGradient(listOf(TabShadowColor, Color.Transparent), 0f, span), size = rectSize)
        }
    }
}

private fun tabLabelStyle(family: FontFamily, color: Color, shadow: Shadow?): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight.Normal,
    fontSize = 24.sp,
    lineHeight = 31.sp,
    color = color,
    shadow = shadow,
)
