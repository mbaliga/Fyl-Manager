package io.github.mbaliga.fylz.ui.cluster

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * The trash can, drawn as parts so it can act.
 *
 * The reference behavior: as dragged files approach, the can notices — it tilts away, lifts a
 * little, and its lid swings open, all continuously with [proximity] so approach and retreat
 * play the same motion forwards and backwards. An `ImageVector` can't split its lid from its
 * body, which is the whole reason this is a Canvas.
 *
 * @param proximity 0 at rest … 1 with the cluster on the can.
 * @param tint the can's colour; the caller decides rest vs danger emphasis.
 */
@Composable
internal fun TrashGlyph(
    proximity: Float,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .graphicsLayer {
                rotationZ = -TILT_DEGREES * proximity
                translationY = -size.height * LIFT_FRACTION * proximity
            },
    ) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.07f

        // Body: a slightly tapered bin with rounded corners and two vent lines.
        val bodyTop = h * 0.30f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.20f, bodyTop),
            size = Size(w * 0.60f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.10f),
        )
        listOf(0.38f, 0.5f, 0.62f).forEach { fraction ->
            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(w * fraction, bodyTop + h * 0.12f),
                end = Offset(w * fraction, bodyTop + h * 0.44f),
                strokeWidth = stroke * 0.6f,
            )
        }

        // Lid: hinged at its back-left corner, swinging open with proximity.
        val lidY = h * 0.24f
        rotate(degrees = -LID_DEGREES * proximity, pivot = Offset(w * 0.16f, lidY)) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.12f, lidY - h * 0.07f),
                size = Size(w * 0.76f, h * 0.10f),
                cornerRadius = CornerRadius(w * 0.05f),
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.38f, lidY - h * 0.16f),
                size = Size(w * 0.24f, h * 0.08f),
                cornerRadius = CornerRadius(w * 0.04f),
            )
        }
    }
}

/**
 * The shredder, for the moment a shred actually happens.
 *
 * Modelled on the reference: a mouth swallowing a sheet, strips raining beneath. Purely
 * decorative motion — the caller owns the deletion and calls this only while it runs, so the
 * animation never outlives (or outpromises) the operation.
 */
@Composable
internal fun ShredderAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shred")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "shredPhase",
    )
    val mouth = MaterialTheme.colorScheme.error
    val sheet = MaterialTheme.colorScheme.surfaceBright
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mouthTop = h * 0.42f
        val mouthHeight = h * 0.16f

        // The sheet, feeding downward into the mouth on a loop.
        val sheetHeight = h * 0.34f
        val sheetTravel = phase * sheetHeight
        drawRoundRect(
            color = sheet,
            topLeft = Offset(w * 0.28f, mouthTop - sheetHeight + sheetTravel),
            size = Size(w * 0.44f, sheetHeight - sheetTravel * 0.4f),
            cornerRadius = CornerRadius(w * 0.02f),
        )

        // The mouth, over the sheet so paper disappears INTO it.
        drawRoundRect(
            color = mouth,
            topLeft = Offset(w * 0.10f, mouthTop),
            size = Size(w * 0.80f, mouthHeight),
            cornerRadius = CornerRadius(w * 0.06f),
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.35f),
            start = Offset(w * 0.16f, mouthTop + mouthHeight * 0.5f),
            end = Offset(w * 0.84f, mouthTop + mouthHeight * 0.5f),
            strokeWidth = h * 0.012f,
        )

        // Strips below, each falling with its own phase and sway.
        val stripTop = mouthTop + mouthHeight
        val strips = 9
        val stripWidth = (w * 0.66f) / strips
        repeat(strips) { index ->
            val local = (phase + index * 0.13f) % 1f
            val x = w * 0.17f + index * stripWidth + sin(local * 6.28f + index) * stripWidth * 0.10f
            drawRoundRect(
                color = sheet.copy(alpha = 1f - local * 0.7f),
                topLeft = Offset(x, stripTop + local * h * 0.30f),
                size = Size(stripWidth * 0.55f, h * 0.20f * (1f - local * 0.35f)),
                cornerRadius = CornerRadius(w * 0.01f),
            )
        }
    }
}

/**
 * The shred confirmation and its running state.
 *
 * The copy is deliberately honest about what shredding is: permanent deletion from Fylz and
 * its Recycle Bin — **not** a forensic wipe. Flash translation layers and wear levelling keep
 * copies no app can reach, so promising "secure erase" would be a lie, and the constellation's
 * rules forbid exactly that promise. What we can truthfully offer is: gone from the app, gone
 * from the bin, unrecoverable *by Fylz*.
 *
 * @param itemCount how many files are about to shred.
 * @param shredding true while the deletion runs; the sheet animates and the buttons lock.
 */
@Composable
internal fun ShredConfirmOverlay(
    itemCount: Int,
    shredding: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(32.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                ShredderAnimation(Modifier.width(160.dp).height(150.dp))
                Text(
                    if (shredding) "Shredding…" else "Shred ${plural(itemCount)}?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Deletes permanently from Fylz and its Recycle Bin. Storage hardware can " +
                        "retain traces beyond any app's reach, so this is not a forensic wipe — " +
                        "it is as gone as software can honestly make it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    TextButton(onClick = onDismiss, enabled = !shredding) { Text("Keep") }
                    Button(
                        onClick = onConfirm,
                        enabled = !shredding,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(if (shredding) "Shredding…" else "Shred")
                    }
                }
            }
        }
    }
}

private fun plural(count: Int) = if (count == 1) "1 file" else "$count files"

private const val TILT_DEGREES = 14f
private const val LIFT_FRACTION = 0.10f
private const val LID_DEGREES = 62f
