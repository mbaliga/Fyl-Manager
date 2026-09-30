package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.theme.BuiltInThemePresets
import io.github.mbaliga.fylz.ui.theme.ThemePaletteStore

/**
 * The Tools room's "Theme" flow (owner request: JSON-based theming with a *mandatory* plain-text
 * preview -- "User should be able to preview the JSON's content as plain text so they are never
 * caught unawares"). `openPicker` shows the built-ins plus "Custom…": choosing a built-in applies
 * it immediately (a bundled preset shipped with the app; it needs no preview). Choosing
 * "Custom…" opens an editable paste step, then a read-only, byte-for-byte-unmodified preview step
 * the user must Apply before anything changes -- Cancel or Back at any point leaves the active
 * theme untouched, per `ThemePaletteStore.setCustom`'s own "never a partial application".
 *
 * One instance lives for `FylzV1Workspace`'s composition ([rememberThemeFlow]); [ThemeFlowHost]
 * renders whichever of its three dialogs is current, alongside `FylzV1App`'s other flow dialogs
 * (`ExtractFlowHost`, `CompressFlowHost`).
 */
class ThemeFlow(private val store: ThemePaletteStore, private val onToast: (String) -> Unit) {
    var pickerOpen by mutableStateOf(false); private set
    var editingCustom by mutableStateOf(false); private set
    var customText by mutableStateOf(""); private set

    /** Frozen by [requestPreview], never edited afterwards -- what the preview dialog shows is
     * always exactly this, the owner's own "never caught unawares" made literal. */
    var previewText by mutableStateOf<String?>(null); private set
    var previewError by mutableStateOf<String?>(null); private set

    val builtInIds: List<String> get() = BuiltInThemePresets.ids

    fun openPicker() { pickerOpen = true }

    fun dismiss() {
        pickerOpen = false
        editingCustom = false
        previewText = null
        previewError = null
    }

    fun chooseBuiltIn(id: String) {
        store.setBuiltIn(id)
        onToast("Theme applied")
        dismiss()
    }

    fun beginCustom() {
        customText = store.lastCustomJson() ?: ""
        editingCustom = true
    }

    fun onCustomTextChange(text: String) { customText = text }

    /** The editing step's own "Preview" button. */
    fun requestPreview() {
        previewError = null
        previewText = customText
    }

    /** Back from the preview step to editing, text intact -- nothing was ever applied. */
    fun backToEditing() { previewText = null; previewError = null }

    fun applyCustom() {
        val text = previewText ?: return
        store.setCustom(text)
            .onSuccess { onToast("Theme applied"); dismiss() }
            .onFailure { error -> previewError = error.message ?: "That JSON could not be applied." }
    }
}

@Composable
fun rememberThemeFlow(store: ThemePaletteStore, onToast: (String) -> Unit): ThemeFlow =
    remember(store) { ThemeFlow(store, onToast) }

@Composable
fun ThemeFlowHost(flow: ThemeFlow) {
    if (!flow.pickerOpen) return
    val preview = flow.previewText
    when {
        preview != null -> ThemeCustomPreviewDialog(
            text = preview,
            error = flow.previewError,
            onBack = flow::backToEditing,
            onApply = flow::applyCustom,
            onCancel = flow::dismiss,
        )
        flow.editingCustom -> ThemeCustomEditDialog(
            text = flow.customText,
            onTextChange = flow::onCustomTextChange,
            onCancel = flow::dismiss,
            onPreview = flow::requestPreview,
        )
        else -> ThemePickerDialog(
            builtInIds = flow.builtInIds,
            onChooseBuiltIn = flow::chooseBuiltIn,
            onCustom = flow::beginCustom,
            onDismiss = flow::dismiss,
        )
    }
}

@Composable
private fun ThemePickerDialog(
    builtInIds: List<String>,
    onChooseBuiltIn: (String) -> Unit,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column {
                builtInIds.forEach { id -> TextButton(onClick = { onChooseBuiltIn(id) }) { Text(BuiltInThemePresets.titleFor(id)) } }
                TextButton(onClick = onCustom) { Text("Custom…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ThemeCustomEditDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onPreview: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Paste theme JSON") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                minLines = 6,
                maxLines = 14,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier.heightIn(min = 160.dp),
            )
        },
        confirmButton = { TextButton(onClick = onPreview, enabled = text.isNotBlank()) { Text("Preview") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** The mandatory preview step: [text] rendered as plain, read-only monospace text -- never a
 * rendered swatch, never reformatted -- so the user sees exactly what they pasted before it can
 * take effect. */
@Composable
private fun ThemeCustomPreviewDialog(
    text: String,
    error: String?,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Review the JSON before applying") },
        text = {
            Column {
                Text(
                    text = text,
                    style = TextStyle(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
                if (error != null) {
                    Text(text = error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("Apply") } },
        dismissButton = {
            Row {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
