package com.xencode.feature.settings.presentation.component

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.xencode.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xencode.R
import com.xencode.core.theme.Spacing
import com.xencode.feature.agent.domain.shizuku.ShizukuState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Bell
import compose.icons.feathericons.Download
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Power
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Sun
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Zap

/**
 * "App permissions" sub-page: central place to view and manage system-level permissions.
 * - Install unknown apps: show grant status; if not granted, tap to jump to system settings to enable it.
 * - Storage access: show grant status; if not granted, tap to request the runtime permission; if permanently denied, also guide to system settings.
 * - Ignore battery optimizations / autostart management: jump to system settings.
 * - Shizuku: show adb shell grant status; if not ready, tap to install/start/request permission.
 * Refreshes permission states when the page resumes (including returning from system settings or the Shizuku app).
 */
@Composable
internal fun AppPermissionsSection(
    shizukuState: ShizukuState,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRefreshShizuku: () -> Unit
) {
    val context = LocalContext.current

    var apkInstallAllowed by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    var storageGranted by remember { mutableStateOf(checkStorageGranted(context)) }
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    var showStorageDeniedDialog by remember { mutableStateOf(false) }

    // Refresh states when returning from the system permission settings page.
    LifecycleResumeEffect(Unit) {
        apkInstallAllowed = context.packageManager.canRequestPackageInstalls()
        storageGranted = checkStorageGranted(context)
        batteryExempt = isIgnoringBatteryOptimizations(context)
        onRefreshShizuku()
        onPauseOrDispose { }
    }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        storageGranted = checkStorageGranted(context)
        // Denied and the system no longer shows the dialog (permanently denied) -> guide to system settings to enable it manually.
        val permanentlyDenied = result.filterValues { !it }.keys.any {
            !shouldShowRationale(context, it)
        }
        if (!storageGranted && permanentlyDenied) showStorageDeniedDialog = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_category_system_permissions))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Download,
                title = stringResource(R.string.settings_permission_apk_install),
                subtitle = stringResource(R.string.settings_permission_apk_install_desc),
                onClick = {
                    if (!apkInstallAllowed) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                },
                trailing = { PermissionStatusText(allowed = apkInstallAllowed) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Folder,
                title = stringResource(R.string.settings_permission_storage),
                subtitle = stringResource(R.string.settings_permission_storage_desc),
                onClick = {
                    if (!storageGranted) {
                        storageLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                },
                trailing = { PermissionStatusText(allowed = storageGranted) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.settings_permission_battery),
                subtitle = stringResource(R.string.settings_permission_battery_desc),
                onClick = {
                    if (!batteryExempt) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                },
                trailing = { PermissionStatusText(allowed = batteryExempt) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Power,
                title = stringResource(R.string.settings_autostart_title),
                subtitle = stringResource(R.string.settings_autostart_subtitle),
                onClick = { OemAutoStartGuide.openAutoStartSettings(context) }
            )
            SettingsDivider()
            val shizukuAction: (() -> Unit)? = when (shizukuState) {
                ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING -> onOpenShizuku
                ShizukuState.PERMISSION_DENIED -> onRequestShizukuPermission
                ShizukuState.READY -> null
            }
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.settings_shizuku),
                subtitle = stringResource(shizukuState.hintRes()),
                onClick = shizukuAction,
                trailing = {
                    Text(
                        text = stringResource(shizukuState.statusRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }

    if (showStorageDeniedDialog) {
        PermissionDeniedDialog(
            title = stringResource(R.string.settings_permission_storage),
            message = stringResource(R.string.settings_permission_storage_denied_desc),
            onGoToSettings = { OemAutoStartGuide.openAppDetails(context) },
            onDismiss = { showStorageDeniedDialog = false }
        )
    }
}

/**
 * "Background running" sub-page: three toggles for keepalive, keep screen on, and agent-completion notifications.
 * The two toggles that need notification permission (keepalive, sound) check and request it before enabling (Android 13+);
 * if the system no longer shows the permission dialog, show a dialog guiding the user to system settings.
 */
@Composable
internal fun BackgroundRunSection(
    keepaliveEnabled: Boolean,
    onToggleKeepalive: (Boolean) -> Unit,
    screenOnEnabled: Boolean,
    onToggleScreenOn: (Boolean) -> Unit,
    agentSoundEnabled: Boolean,
    onToggleAgentSound: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var showNotificationDeniedDialog by remember { mutableStateOf(false) }
    // Callback of the target toggle to turn on once notification permission is granted.
    var pendingNotificationToggle by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    // With targetSdk 28 on Android 13+, requesting notification permission shows no system dialog
    // and immediately calls back denied; the only option is to guide the user to system settings.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingNotificationToggle
        pendingNotificationToggle = null
        if (granted) {
            pending?.invoke(true)
        } else {
            showNotificationDeniedDialog = true
        }
    }

    fun toggleWithNotificationPermission(onToggle: (Boolean) -> Unit, enabled: Boolean) {
        if (!enabled || notificationsGranted(context)) {
            onToggle(enabled)
            return
        }
        pendingNotificationToggle = onToggle
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(R.string.settings_keepalive_title),
                subtitle = stringResource(R.string.settings_keepalive_subtitle),
                trailing = {
                    AppSwitch(
                        checked = keepaliveEnabled,
                        onCheckedChange = { enabled ->
                            toggleWithNotificationPermission(onToggleKeepalive, enabled)
                        }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Sun,
                title = stringResource(R.string.settings_screen_on_title),
                subtitle = stringResource(R.string.settings_screen_on_subtitle),
                trailing = {
                    AppSwitch(
                        checked = screenOnEnabled,
                        onCheckedChange = onToggleScreenOn
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Bell,
                title = stringResource(R.string.settings_agent_sound_title),
                subtitle = stringResource(R.string.settings_agent_sound_subtitle),
                trailing = {
                    AppSwitch(
                        checked = agentSoundEnabled,
                        onCheckedChange = { enabled ->
                            toggleWithNotificationPermission(onToggleAgentSound, enabled)
                        }
                    )
                }
            )
        }
    }

    if (showNotificationDeniedDialog) {
        PermissionDeniedDialog(
            title = stringResource(R.string.settings_permission_notification_title),
            message = stringResource(R.string.settings_permission_notification_denied_desc),
            onGoToSettings = { OemAutoStartGuide.openAppDetails(context) },
            onDismiss = { showNotificationDeniedDialog = false }
        )
    }
}

/** Right-side status text for each Shizuku state. */
@StringRes
private fun ShizukuState.statusRes(): Int = when (this) {
    ShizukuState.NOT_INSTALLED -> R.string.settings_shizuku_status_not_installed
    ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_status_not_running
    ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_status_denied
    ShizukuState.READY -> R.string.settings_shizuku_status_ready
}

/** Subtitle (tap hint) for each Shizuku state. */
@StringRes
private fun ShizukuState.hintRes(): Int = when (this) {
    ShizukuState.NOT_INSTALLED -> R.string.settings_shizuku_hint_not_installed
    ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_hint_not_running
    ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_hint_denied
    ShizukuState.READY -> R.string.settings_shizuku_hint_ready
}

/** When a permission cannot be granted via the system dialog, remind the user to enable it manually in system settings. */
@Composable
private fun PermissionDeniedDialog(
    title: String,
    message: String,
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onGoToSettings()
                }
            ) {
                Text(stringResource(R.string.common_go_to_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/** Android 13+ requires notification permission; lower versions do not. */
private fun notificationsGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** Whether the system would still show the permission dialog (false and not granted means permanently denied). */
private fun shouldShowRationale(context: android.content.Context, permission: String): Boolean =
    (context as? android.app.Activity)?.shouldShowRequestPermissionRationale(permission) ?: false

/** Status text on the right of permission rows: allowed / not allowed. */
@Composable
private fun PermissionStatusText(allowed: Boolean) {
    Text(
        text = stringResource(
            if (allowed) R.string.settings_permission_granted else R.string.settings_permission_denied
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun checkStorageGranted(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}