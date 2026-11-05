package com.xencode.feature.credentials.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.xencode.core.ui.AppTextField
import com.xencode.core.ui.dialogTextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.xencode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import androidx.compose.ui.res.stringResource
import com.xencode.R

/**
 * Login dialog shown when a pull/push lacks https credentials. host is pre-filled read-only and the user enters username/token; on confirm the callback reports them back,
 * on cancel the caller gives up. This is the unified dialog for missing git credentials across all three surfaces (UI / AI Bash / interactive terminal) —
 * both the UI path and the command-line requests observed by [com.xencode.feature.credentials.data.CredentialRequestBridge] reuse this component.
 *
 * @param host the remote host (read-only prefill, from the git credential protocol's host field)
 * @param onConfirm reports the user-entered username/token (trimmed); the caller persists them + feeds them back to the git helper. Enabled only when both fields are non-blank.
 * @param onDismiss cancel: the caller writes a cancel response and the helper exits non-zero so git reports an auth failure (same behavior as having no credentials).
 * @param usernameHint git may already carry a prefilled username in the request (e.g. a u in the URL); used as the initial value when non-blank; usually empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialPromptDialog(
    host: String,
    onConfirm: (username: String, token: String) -> Unit,
    onDismiss: () -> Unit,
    usernameHint: String? = null
) {
    var username by remember(host, usernameHint) { mutableStateOf(usernameHint ?: "") }
    var token by remember(host) { mutableStateOf("") }
    var tokenVisible by remember(host) { mutableStateOf(false) }
    val canSave = username.trim().isNotBlank() && token.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.credential_prompt_title, host)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AppTextField(
                    value = host,
                    onValueChange = { /* host comes from the remote, read-only */ },
                    label = stringResource(R.string.credential_remote_host),
                    singleLine = true,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
                AppTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.common_username),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
                AppTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(R.string.credential_token),
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                                contentDescription = if (tokenVisible) stringResource(R.string.common_hide) else stringResource(R.string.common_show)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
                Text(
                    text = stringResource(R.string.credential_prompt_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canSave) onConfirm(username.trim(), token) },
                enabled = canSave
            ) { Text(stringResource(R.string.credential_save_and_retry)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
