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
 * type the full phrase before the destructive action becomes available.
 */
@Composable
fun PermanentDeleteConfirmationDialog(
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember(displayName) { mutableStateOf(TextFieldValue()) }
    val confirmed = typed.text.trim() == CONFIRMATION_PHRASE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete permanently?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "This permanently deletes “$displayName”. It cannot be restored by Fylz.",
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
