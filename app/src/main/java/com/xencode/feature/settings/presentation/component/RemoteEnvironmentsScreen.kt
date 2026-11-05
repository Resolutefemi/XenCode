package com.xencode.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xencode.R
import com.xencode.core.theme.Spacing
import com.xencode.core.theme.semanticColors
import com.xencode.core.ui.AppSwitch
import com.xencode.core.ui.AppTextField
import com.xencode.core.ui.FloatingTabBar
import com.xencode.core.ui.FloatingTabItem
import com.xencode.feature.settings.data.repository.InstaboxConfig
import com.xencode.feature.settings.presentation.InstaboxTestUiState
import com.xencode.feature.settings.presentation.RemoteEnvironmentsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Tool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteEnvironmentsScreen(
    viewModel: RemoteEnvironmentsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val saved by viewModel.config.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val saveNotice by viewModel.saveNotice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.remote_env_saved)

    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(saveNotice) {
        if (saveNotice != null) {
            snackbarHostState.showSnackbar(savedMessage)
            viewModel.clearSaveNotice()
        }
    }

    if (selectedProvider == "instabox") {
        InstaboxDetailScreen(
            saved = saved,
            testState = testState,
            snackbarHostState = snackbarHostState,
            onNavigateBack = { selectedProvider = null },
            onSave = viewModel::save,
            onTest = viewModel::testConnection
        )
        return
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(stringResource(R.string.settings_remote_environments)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.remote_env_providers_header))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Cloud,
                    title = stringResource(R.string.remote_env_instabox),
                    subtitle = if (saved.enabled) {
                        stringResource(R.string.remote_env_instabox_ready)
                    } else {
                        stringResource(R.string.remote_env_instabox_subtitle)
                    },
                    onClick = { selectedProvider = "instabox" }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Cpu,
                    title = stringResource(R.string.remote_env_e2b),
                    subtitle = stringResource(R.string.remote_env_e2b_subtitle),
                    enabled = false
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstaboxDetailScreen(
    saved: InstaboxConfig,
    testState: InstaboxTestUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onSave: (InstaboxConfig) -> Unit,
    onTest: (InstaboxConfig) -> Unit
) {
    var enabled by rememberSaveable(saved.enabled) { mutableStateOf(saved.enabled) }
    var apiKey by rememberSaveable(saved.apiKey) { mutableStateOf(saved.apiKey) }
    var apiUrl by rememberSaveable(saved.apiUrl) { mutableStateOf(saved.apiUrl) }
    var template by rememberSaveable(saved.template) { mutableStateOf(saved.template) }
    var machineId by rememberSaveable(saved.machineId) { mutableStateOf(saved.machineId) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val configScroll = rememberScrollState()
    val arsenalScroll = rememberScrollState()
    val tabsScrolling by remember {
        derivedStateOf { configScroll.isScrollInProgress || arsenalScroll.isScrollInProgress }
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(stringResource(R.string.remote_env_instabox)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                when (tab) {
                    0 -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(configScroll)
                            .imePadding()
                            .padding(horizontal = Spacing.lg)
                            .padding(bottom = 96.dp)
                    ) {
                        SettingsGroupHeader(text = stringResource(R.string.remote_env_status_header))
                        SettingsGroup {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.common_enabled),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = stringResource(R.string.remote_env_enabled_subtitle),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                AppSwitch(
                                    checked = enabled,
                                    onCheckedChange = { enabled = it }
                                )
                            }
                        }

                        SettingsGroupHeader(text = stringResource(R.string.remote_env_keys_header))
                        SettingsGroup {
                            AppTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = stringResource(R.string.remote_env_api_key),
                                placeholder = stringResource(R.string.remote_env_api_key_hint),
                                singleLine = true,
                                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    Icon(
                                        imageVector = if (apiKeyVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                                        contentDescription = stringResource(
                                            if (apiKeyVisible) R.string.provider_hide_api_key else R.string.provider_show_api_key
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable { apiKeyVisible = !apiKeyVisible }
                                            .padding(2.dp)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                            )
                            AppTextField(
                                value = apiUrl,
                                onValueChange = { apiUrl = it },
                                label = stringResource(R.string.remote_env_api_url),
                                placeholder = InstaboxConfig.DEFAULT_API_URL,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                            )
                            AppTextField(
                                value = template,
                                onValueChange = { template = it },
                                label = stringResource(R.string.remote_env_template),
                                placeholder = InstaboxConfig.DEFAULT_TEMPLATE,
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                            )
                            AppTextField(
                                value = machineId,
                                onValueChange = { machineId = it },
                                label = stringResource(R.string.remote_env_machine_id),
                                placeholder = stringResource(R.string.remote_env_machine_id_hint),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }

                        Spacer(Modifier.height(Spacing.md))
                        Button(
                            onClick = {
                                onSave(
                                    InstaboxConfig(
                                        enabled = enabled,
                                        apiKey = apiKey,
                                        apiUrl = apiUrl.ifBlank { InstaboxConfig.DEFAULT_API_URL },
                                        template = template.ifBlank { InstaboxConfig.DEFAULT_TEMPLATE },
                                        machineId = machineId
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(arsenalScroll)
                            .padding(horizontal = Spacing.lg)
                            .padding(bottom = 96.dp)
                    ) {
                        SettingsGroupHeader(text = stringResource(R.string.remote_env_probe_header))
                        SettingsGroup {
                            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                                Text(
                                    text = stringResource(R.string.remote_env_probe_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(Spacing.md))
                                Button(
                                    onClick = {
                                        onTest(
                                            InstaboxConfig(
                                                enabled = enabled,
                                                apiKey = apiKey,
                                                apiUrl = apiUrl.ifBlank { InstaboxConfig.DEFAULT_API_URL },
                                                template = template.ifBlank { InstaboxConfig.DEFAULT_TEMPLATE },
                                                machineId = machineId
                                            )
                                        )
                                    },
                                    enabled = testState !is InstaboxTestUiState.Running && apiKey.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (testState is InstaboxTestUiState.Running) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(Spacing.sm))
                                    }
                                    Text(stringResource(R.string.remote_env_test_connection))
                                }
                                when (val state = testState) {
                                    is InstaboxTestUiState.Success -> {
                                        Spacer(Modifier.height(Spacing.sm))
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    is InstaboxTestUiState.Failure -> {
                                        Spacer(Modifier.height(Spacing.sm))
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    else -> {}
                                }
                            }
                        }

                        SettingsGroupHeader(text = stringResource(R.string.remote_env_arsenal_header))
                        InstaboxToolGroups()
                    }
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Sliders, stringResource(R.string.provider_config)),
                    FloatingTabItem(FeatherIcons.Tool, stringResource(R.string.remote_env_tab_arsenal))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

private data class InstaboxAbility(
    val titleRes: Int,
    val bodyRes: Int,
    val actions: List<Pair<Int, Int>>
)

@Composable
private fun InstaboxToolGroups() {
    val groups = listOf(
        InstaboxAbility(
            R.string.remote_env_group_lifecycle,
            R.string.remote_env_group_lifecycle_desc,
            listOf(
                R.string.remote_env_action_create to R.string.remote_env_action_create_desc,
                R.string.remote_env_action_connect to R.string.remote_env_action_connect_desc,
                R.string.remote_env_action_info to R.string.remote_env_action_info_desc,
                R.string.remote_env_action_list to R.string.remote_env_action_list_desc,
                R.string.remote_env_action_pause to R.string.remote_env_action_pause_desc,
                R.string.remote_env_action_resume to R.string.remote_env_action_resume_desc,
                R.string.remote_env_action_stop to R.string.remote_env_action_stop_desc,
                R.string.remote_env_action_kill to R.string.remote_env_action_kill_desc
            )
        ),
        InstaboxAbility(
            R.string.remote_env_group_shell,
            R.string.remote_env_group_shell_desc,
            listOf(
                R.string.remote_env_action_run_command to R.string.remote_env_action_run_command_desc,
                R.string.remote_env_action_run_code to R.string.remote_env_action_run_code_desc,
                R.string.remote_env_action_install to R.string.remote_env_action_install_desc,
                R.string.remote_env_action_processes to R.string.remote_env_action_processes_desc
            )
        ),
        InstaboxAbility(
            R.string.remote_env_group_files,
            R.string.remote_env_group_files_desc,
            listOf(
                R.string.remote_env_action_files_list to R.string.remote_env_action_files_list_desc,
                R.string.remote_env_action_files_read to R.string.remote_env_action_files_read_desc,
                R.string.remote_env_action_files_write to R.string.remote_env_action_files_write_desc,
                R.string.remote_env_action_files_mkdir to R.string.remote_env_action_files_mkdir_desc
            )
        ),
        InstaboxAbility(
            R.string.remote_env_group_desktop,
            R.string.remote_env_group_desktop_desc,
            listOf(
                R.string.remote_env_action_desktop_start to R.string.remote_env_action_desktop_start_desc,
                R.string.remote_env_action_desktop_shot to R.string.remote_env_action_desktop_shot_desc,
                R.string.remote_env_action_desktop_control to R.string.remote_env_action_desktop_control_desc,
                R.string.remote_env_action_desktop_stream to R.string.remote_env_action_desktop_stream_desc
            )
        ),
        InstaboxAbility(
            R.string.remote_env_group_net,
            R.string.remote_env_group_net_desc,
            listOf(
                R.string.remote_env_action_get_url to R.string.remote_env_action_get_url_desc,
                R.string.remote_env_action_ports to R.string.remote_env_action_ports_desc
            )
        )
    )
    groups.forEach { group ->
        var expanded by rememberSaveable(key = stringResource(group.titleRes)) { mutableStateOf(false) }
        SettingsGroup(modifier = Modifier.padding(bottom = Spacing.sm)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(group.titleRes),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = stringResource(group.bodyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    group.actions.forEach { (title, body) ->
                        SettingsDivider()
                        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 10.dp)) {
                            Text(
                                text = stringResource(title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
