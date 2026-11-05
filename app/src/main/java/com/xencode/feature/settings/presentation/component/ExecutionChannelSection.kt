package com.xencode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xencode.R
import com.xencode.core.theme.Spacing
import com.xencode.core.theme.semanticColors
import com.xencode.feature.agent.domain.container.ContainerProfile
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.presentation.RootProbeState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Server
import compose.icons.feathericons.Shield

/**
 * "Command execution channel" sub-settings page: choose the execution environment for AI commands/files/terminal —
 * sandbox container (PRoot) / device ROOT (su) / remote SSH.
 *
 * Switching to device ROOT requires a countdown confirmation ([CountdownConfirmDialog]), and switching is blocked while the su probe is unavailable;
 * sandbox/remote are restored via the container profile system (the ViewModel's switchExecutionChannel handles image binding).
 */
@Composable
internal fun ExecutionChannelSection(
    executionMode: ExecutionMode,
    profiles: List<ContainerProfile>,
    activeProfileId: String,
    defaultContainerId: String,
    rootProbeState: RootProbeState,
    onSwitchChannel: (ExecutionMode) -> Unit,
    onProbeRoot: () -> Unit
) {
    var showDeviceConfirm by remember { mutableStateOf(false) }
    var showRootBlocked by remember { mutableStateOf(false) }

    // Probe ROOT automatically on first entry so the user immediately sees the status
    LaunchedEffect(Unit) {
        if (rootProbeState == RootProbeState.UNKNOWN) onProbeRoot()
    }

    // Image names for display: sandbox uses the active local image (else default container/built-in), remote uses the active remote image (else the first one)
    val sandboxImageName = profiles.firstOrNull { it.id == activeProfileId && it.mode == ExecutionMode.LOCAL_PROOT }
        ?: profiles.firstOrNull { it.id == defaultContainerId && it.mode == ExecutionMode.LOCAL_PROOT }
        ?: profiles.firstOrNull { it.mode == ExecutionMode.LOCAL_PROOT }
    val remoteImage = profiles.firstOrNull { it.id == activeProfileId && it.mode == ExecutionMode.REMOTE_SSH }
        ?: profiles.firstOrNull { it.mode == ExecutionMode.REMOTE_SSH }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_execution_channel))
        SettingsGroup {
            ChannelRow(
                icon = FeatherIcons.Box,
                title = stringResource(R.string.channel_sandbox),
                subtitle = buildString {
                    append(stringResource(R.string.channel_sandbox_desc))
                    sandboxImageName?.let {
                        append(" · ")
                        append(stringResource(R.string.channel_image_fmt, it.name))
                    }
                },
                selected = executionMode == ExecutionMode.LOCAL_PROOT,
                onClick = { onSwitchChannel(ExecutionMode.LOCAL_PROOT) }
            )
            SettingsDivider()
            ChannelRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(R.string.channel_device),
                subtitle = stringResource(R.string.channel_device_desc),
                selected = executionMode == ExecutionMode.DEVICE_ROOT,
                danger = true,
                onClick = {
                    if (rootProbeState == RootProbeState.UNAVAILABLE) {
                        showRootBlocked = true
                    } else {
                        showDeviceConfirm = true
                    }
                }
            )
            SettingsDivider()
            ChannelRow(
                icon = FeatherIcons.Server,
                title = stringResource(R.string.channel_remote),
                subtitle = if (remoteImage != null) {
                    stringResource(R.string.channel_remote_desc) + " · " +
                        stringResource(R.string.channel_image_fmt, remoteImage.name)
                } else {
                    stringResource(R.string.channel_remote_no_image)
                },
                selected = executionMode == ExecutionMode.REMOTE_SSH,
                enabled = remoteImage != null,
                onClick = { onSwitchChannel(ExecutionMode.REMOTE_SSH) }
            )
        }

        // ── Device ROOT: status detection + notes ──
        SettingsGroupHeader(text = stringResource(R.string.channel_device_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(R.string.channel_root_status),
                onClick = null,
                trailing = {
                    Text(
                        text = stringResource(
                            when (rootProbeState) {
                                RootProbeState.UNKNOWN -> R.string.channel_root_unknown
                                RootProbeState.PROBING -> R.string.channel_root_probing
                                RootProbeState.AVAILABLE -> R.string.channel_root_ok
                                RootProbeState.UNAVAILABLE -> R.string.channel_root_fail
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (rootProbeState) {
                            RootProbeState.AVAILABLE -> MaterialTheme.colorScheme.primary
                            RootProbeState.UNAVAILABLE -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.semanticColors.subtleText
                        }
                    )
                    Spacer(Modifier.width(Spacing.md))
                    TextButton(
                        onClick = onProbeRoot,
                        enabled = rootProbeState != RootProbeState.PROBING
                    ) {
                        Text(stringResource(R.string.channel_root_detect))
                    }
                }
            )
        }
        Text(
            text = stringResource(R.string.channel_device_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
    }

    if (showDeviceConfirm) {
        CountdownConfirmDialog(
            title = stringResource(R.string.channel_device_confirm_title),
            message = stringResource(R.string.channel_device_confirm_message),
            onConfirm = {
                showDeviceConfirm = false
                onSwitchChannel(ExecutionMode.DEVICE_ROOT)
            },
            onDismiss = { showDeviceConfirm = false }
        )
    }

    if (showRootBlocked) {
        AlertDialog(
            onDismissRequest = { showRootBlocked = false },
            title = { Text(stringResource(R.string.channel_root_blocked_title)) },
            text = { Text(stringResource(R.string.channel_root_blocked_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRootBlocked = false
                    onProbeRoot()
                }) { Text(stringResource(R.string.channel_root_detect)) }
            },
            dismissButton = {
                TextButton(onClick = { showRootBlocked = false }) {
                    Text(stringResource(R.string.common_got_it))
                }
            }
        )
    }
}

/** Channel selection row: icon + title/subtitle + radio button; [danger] enables the warning colors for device ROOT. */
@Composable
private fun ChannelRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                danger -> MaterialTheme.colorScheme.error
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}
