package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The one password entry point shared across every archive format and both directions -- creating
 * a new protected archive and opening one that already exists (M3.9, MASTER_PLAN "Password
 * prompt"). Pulled out of `ArchiveToolsOverlay`'s own private `ArchivePasswordDialog` (M3.5c/M3.4c)
 * so every call site uses the same dialog rather than each growing its own; `io.github.mbaliga
 * .fylz.ui.FylzV1App`'s legacy-encrypted-ZIP extract flow had one such ad hoc copy (a plain
 * `String` field, never wiped on its own), now replaced by this.
 *
 * [confirmNewPassword] is the create-side shape: an "Encrypt" switch (off by default -- a plain
 * archive needs no password at all) and, once it is on, a confirmation field so a typo is caught
 * before the secret is set, since there is nothing yet to check it against. Off, this is the
 * open-side shape: a single required password field for a secret that already exists -- a wrong
 * guess simply fails to open later, so nothing here can validate it.
 *
 * [offerRemember] shows the "Remember for this session" tick (unticked, the default) that lets a
 * caller skip asking again for the same archive within this process; callers wire it to
 * [io.github.mbaliga.fylz.archive.ArchivePasswordSession] themselves -- this composable only
 * reports the person's choice back through [onConfirm], never touching the session store. It is
 * `false` for a brand new archive being created: there is no archive identity yet to remember a
 * password against until a destination is actually chosen.
 *
 * [onConfirm] receives the password as a [CharArray] (`null` only when [confirmNewPassword] and
 * the person left "Encrypt" off, i.e. "no password at all") and the remember choice. The caller
 * owns that array from there on and must wipe it (`fill('\u0000')`) once done, exactly as
 * `io.github.mbaliga.fylz.data.ArchiveService.createZip`/`extractZip` already do in their own
 * `finally` -- this dialog never keeps a reference to what it hands back.
 */
@Composable
fun PasswordPromptDialog(
    title: String,
    confirmNewPassword: Boolean,
    offerRemember: Boolean,
    confirmLabel: String = "Continue",
    onDismiss: () -> Unit,
    onConfirm: (password: CharArray?, remember: Boolean) -> Unit,
) {
    var encrypted by remember { mutableStateOf(!confirmNewPassword) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var rememberForSession by remember { mutableStateOf(false) }
    val valid = if (!encrypted) {
        confirmNewPassword
    } else if (confirmNewPassword) {
        password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH && password == confirmation
    } else {
        password.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (confirmNewPassword) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Encrypt with AES-256")
                            Text(
                                "Leave disabled to create a standard ZIP.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = encrypted, onCheckedChange = { encrypted = it })
                    }
                }
                if (encrypted) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(MAX_PASSWORD_LENGTH) },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (confirmNewPassword) {
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it.take(MAX_PASSWORD_LENGTH) },
                            label = { Text("Confirm password") },
                            visualTransformation = PasswordVisualTransformation(),
                            supportingText = {
                                Text(
                                    when {
                                        password.length < MIN_PASSWORD_LENGTH -> "Use at least $MIN_PASSWORD_LENGTH characters."
                                        confirmation.isNotEmpty() && password != confirmation -> "Passwords do not match."
                                        else -> "Fylz cannot recover a forgotten archive password."
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (offerRemember) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = rememberForSession, onCheckedChange = { rememberForSession = it })
                            Column {
                                Text("Remember for this session")
                                Text(
                                    "Kept in memory only; forgotten when Fylz closes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = if (encrypted) password.toCharArray() else null
                    val remember = encrypted && rememberForSession
                    password = ""
                    confirmation = ""
                    onConfirm(result, remember)
                },
                enabled = valid,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIN_PASSWORD_LENGTH = 8
private const val MAX_PASSWORD_LENGTH = 256
