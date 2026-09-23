package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

private const val CONFIRMATION_PHRASE = "DELETE PERMANENTLY"

/**
 * High-friction confirmation for the only irreversible delete path in Fylz.
 *
 * Ordinary delete never uses this dialog; it always routes through the Recycle Bin. The user must
 * type the full phrase before the destructive action becomes available. Used both for deleting a
 * single recycle bin item (pass [itemName]) and for Empty Recycle Bin (P0.8, contract §2.5-2.8;
 * [itemCount] items, no single [itemName]).
 */
@Composable
fun PermanentDeleteConfirmationDialog(
    itemCount: Int,
    totalBytes: Long?,
    itemName: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember(itemCount, totalBytes, itemName) { mutableStateOf(TextFieldValue()) }
    val confirmed = typed.text.trim() == CONFIRMATION_PHRASE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete permanently?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = permanentDeleteMessage(itemCount, totalBytes, itemName),
                    color = MaterialTheme.colorScheme.error,
                )
                Text("Type $CONFIRMATION_PHRASE to continue.")
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Confirmation phrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        if (typed.text.isNotEmpty() && !confirmed) {
                            Text("The phrase must match exactly.")
                        }
                    },
                    isError = typed.text.isNotEmpty() && !confirmed,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = confirmed) {
                Text("Delete permanently")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** The confirmation copy's count and size wording, pulled out so it's testable without Compose. */
internal fun permanentDeleteMessage(itemCount: Int, totalBytes: Long?, itemName: String? = null): String {
    val subject = if (itemCount == 1 && itemName != null) {
        "“$itemName”"
    } else {
        val itemWord = if (itemCount == 1) "item" else "items"
        "$itemCount $itemWord"
    }
    val sizeSuffix = totalBytes?.let { " (${formatBytes(it)})" }.orEmpty()
    return "This permanently deletes $subject$sizeSuffix. It cannot be restored by Fylz."
}

/** Sums whatever sizes are actually known; null only when none of them are (never 0 for an empty
 * list a caller wouldn't be showing this dialog for in the first place). */
internal fun totalKnownBytes(sizes: List<Long?>): Long? {
    val known = sizes.filterNotNull()
    return if (known.isEmpty()) null else known.sum()
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}
