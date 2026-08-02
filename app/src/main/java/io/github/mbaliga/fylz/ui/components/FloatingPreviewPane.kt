package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * An original Fylz implementation of the movable workspace pane pattern used across the user's
 * constellation. It shares the interaction idea, not private Fonebrew source or assets.
 */
@Composable
fun FloatingPreviewPane(
    onDock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = constraints.maxWidth
        val containerHeightPx = constraints.maxHeight
        val minWidthPx = with(density) { 280.dp.roundToPx() }
        val minHeightPx = with(density) { 280.dp.roundToPx() }
        val initialWidthPx = with(density) { 430.dp.roundToPx() }
        val initialHeightPx = with(density) { 600.dp.roundToPx() }

        var paneSize by remember {
            mutableStateOf(IntSize(initialWidthPx, initialHeightPx))
        }
        var paneOffset by remember {
            mutableStateOf(Offset(72f, 110f))
        }

        LaunchedEffect(containerWidthPx, containerHeightPx) {
            val maxWidthPx = containerWidthPx.coerceAtLeast(minWidthPx)
            val maxHeightPx = containerHeightPx.coerceAtLeast(minHeightPx)
            paneSize = IntSize(
                paneSize.width.coerceIn(minWidthPx, maxWidthPx),
                paneSize.height.coerceIn(minHeightPx, maxHeightPx),
            )
            paneOffset = Offset(
                paneOffset.x.coerceIn(0f, (maxWidthPx - paneSize.width).coerceAtLeast(0).toFloat()),
                paneOffset.y.coerceIn(0f, (maxHeightPx - paneSize.height).coerceAtLeast(0).toFloat()),
            )
        }

        val widthDp = with(density) { paneSize.width.toDp() }
        val heightDp = with(density) { paneSize.height.toDp() }

        Surface(
            modifier = Modifier
                .offset {
                    IntOffset(paneOffset.x.roundToInt(), paneOffset.y.roundToInt())
                }
                .size(widthDp, heightDp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .pointerInput(paneSize, containerWidthPx, containerHeightPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val maxX = (containerWidthPx - paneSize.width)
                                        .coerceAtLeast(0)
                                        .toFloat()
                                    val maxY = (containerHeightPx - paneSize.height)
                                        .coerceAtLeast(0)
                                        .toFloat()
                                    paneOffset = Offset(
                                        (paneOffset.x + dragAmount.x).coerceIn(0f, maxX),
                                        (paneOffset.y + dragAmount.y).coerceIn(0f, maxY),
                                    )
                                }
                            }
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.DragHandle, contentDescription = "Move preview pane")
                        Text(
                            "Preview",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                        IconButton(onClick = onDock) {
                            Icon(Icons.Outlined.PushPin, contentDescription = "Dock preview")
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close preview")
                        }
                    }
                    HorizontalDivider()
                    Box(Modifier.weight(1f)) { content() }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            MaterialTheme.shapes.small,
                        )
                        .pointerInput(containerWidthPx, containerHeightPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val maxWidthPx = (containerWidthPx - paneOffset.x.roundToInt())
                                    .coerceAtLeast(minWidthPx)
                                val maxHeightPx = (containerHeightPx - paneOffset.y.roundToInt())
                                    .coerceAtLeast(minHeightPx)
                                paneSize = IntSize(
                                    (paneSize.width + dragAmount.x.roundToInt())
                                        .coerceIn(minWidthPx, maxWidthPx),
                                    (paneSize.height + dragAmount.y.roundToInt())
                                        .coerceIn(minHeightPx, maxHeightPx),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↘", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
