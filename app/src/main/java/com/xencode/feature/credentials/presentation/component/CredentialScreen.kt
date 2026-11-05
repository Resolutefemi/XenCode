package com.xencode.feature.credentials.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.xencode.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xencode.R
import com.xencode.core.theme.Radius
import com.xencode.core.theme.Spacing
import com.xencode.core.theme.semanticColors
import com.xencode.core.ui.FloatingTabBar
import com.xencode.core.ui.FloatingTabItem
import com.xencode.core.ui.SwipeToDeleteRow
import com.xencode.feature.credentials.domain.model.GitCredential
import com.xencode.feature.credentials.presentation.CredentialViewModel
import com.xencode.feature.git.presentation.component.SectionHeader
import com.xencode.feature.settings.presentation.component.SettingsDivider
import com.xencode.feature.settings.presentation.component.SettingsGroup
import com.xencode.feature.settings.presentation.component.SettingsGroup
import com.xencode.feature.settings.presentation.component.SettingsRow
import com.xencode.feature.settings.presentation.component.settingsLightMode
import com.xencode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Github
import compose.icons.feathericons.Key
import compose.icons.feathericons.Plus
import compose.icons.feathericons.User

/**
 * Standalone page for credentials and identity: a floating bottom tab bar switches between the "identity" and "credentials" tabs.
 * The top bar's "+" adds a credential; edit/add states open via [CredentialEditorSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialScreen(
    viewModel: CredentialViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-read the identity on every entry: the user may have changed the project-level/global identity in a terminal, avoiding a stale empty echo.
    LaunchedEffect(Unit) { viewModel.refreshIdentity() }

    // toast → Snackbar, consumed once.
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // Both tabs' scroll states are hoisted to the page level, aggregating an "is scrolling" signal used to fade the bottom tab bar while scrolling.
    val identityScrollState = rememberScrollState()
    val credentialListState = rememberLazyListState()
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val tabsScrolling by remember {
        derivedStateOf {
            identityScrollState.isScrollInProgress || credentialListState.isScrollInProgress
        }
    }

    // editingCredential != null -> edit existing; editingCredential == null && isAddingCredential -> add new; otherwise list state.
    var editingCredential by remember { mutableStateOf<GitCredential?>(null) }
    var isAddingCredential by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_credentials_and_identity)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { isAddingCredential = true }) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.credential_add))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                when (tab) {
                    0 -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(identityScrollState)
                            .padding(horizontal = Spacing.lg)
                            // Reserve the floating tab bar's height at the bottom: content scrolls past the tab area and fades under the mask,
                            // and when scrolled to the end the last item stops above the tab bar, unobstructed.
                            .padding(bottom = 70.dp)
                    ) {
                        GitUserIdentityCard(
                            initialName = state.userName,
                            initialEmail = state.userEmail,
                            initialRepoUrl = state.repoUrl,
                            globalHint = state.globalUserName,
                            onSave = viewModel::saveUserIdentity
                        )
                        if (state.isRemote) {
                            Spacer(Modifier.height(Spacing.md))
                            SettingsGroup {
                                SettingsRow(
                                    icon = FeatherIcons.Key,
                                    title = stringResource(R.string.credential_auto_inject_title),
                                    subtitle = stringResource(R.string.credential_auto_inject_hint),
                                    trailing = {
                                        AppSwitch(
                                            checked = state.autoInjectEnabled,
                                            onCheckedChange = viewModel::setAutoInject
                                        )
                                    }
                                )
                            }
                        }
                    }
                    1 -> if (state.credentials.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.lg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.credential_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = credentialListState,
                            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 70.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            item(key = "header") {
                                SectionHeader(stringResource(R.string.git_credentials_count, state.credentials.size))
                            }
                            item(key = "credential-group") {
                                SettingsGroup {
                                    state.credentials.forEachIndexed { index, cred ->
                                        if (index > 0) SettingsDivider()
                                        CredentialItem(
                                            credential = cred,
                                            onEdit = { editingCredential = cred },
                                            onDelete = { viewModel.deleteCredential(cred.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.User, stringResource(R.string.git_tab_identity)),
                    FloatingTabItem(FeatherIcons.Key, stringResource(R.string.git_tab_credentials))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    val editing = editingCredential
    if (editing != null) {
        CredentialEditorSheet(
            initial = editing,
            onDismiss = { editingCredential = null },
            onSave = { viewModel.saveCredential(it); editingCredential = null }
        )
    }

    if (isAddingCredential) {
        CredentialEditorSheet(
            initial = null,
            onDismiss = { isAddingCredential = false },
            onSave = { viewModel.saveCredential(it); isAddingCredential = false }
        )
    }

    // Remote mode, first entry to this server: ask whether xencode should auto-inject credentials
    if (state.showAutoInjectPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissAutoInjectPrompt(false) },
            title = { Text(stringResource(R.string.credential_auto_inject_prompt_title)) },
            text = { Text(stringResource(R.string.credential_auto_inject_prompt_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissAutoInjectPrompt(true) }) {
                    Text(stringResource(R.string.credential_auto_inject_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissAutoInjectPrompt(false) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun CredentialItem(
    credential: GitCredential,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left icon tile: same as the container/remote-connection rows; the icon shows the platform logo by host (github/gitee), falling back to a key otherwise.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                PlatformIcon(
                    host = credential.host,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = credential.host,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    HostBadge(host = credential.host)
                }
                Text(
                    text = credential.username,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Right chevron: same as the remote-connection/MCP rows; the whole row taps to edit.
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = stringResource(R.string.common_edit),
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Platform icon: shows the matching Git hosting service logo by host (GitHub / Gitee currently supported),
 * falling back to a generic key icon otherwise. Monochrome tint, consistent with the system logos on the container/image pages.
 */
@Composable
private fun PlatformIcon(host: String, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        host.contains("github", ignoreCase = true) ->
            Icon(FeatherIcons.Github, contentDescription = null, tint = tint, modifier = modifier)
        host.contains("gitee", ignoreCase = true) ->
            Icon(painterResource(R.drawable.logo_gitee), contentDescription = null, tint = tint, modifier = modifier)
        else ->
            Icon(FeatherIcons.Key, contentDescription = null, tint = tint, modifier = modifier)
    }
}

/** Remote host brand badge: recognizes common Git hosting services by host and shows the brand name; unknown hosts show the first domain segment. */
@Composable
private fun HostBadge(host: String) {
    val label = remember(host) { hostBadgeLabel(host) }
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

private fun hostBadgeLabel(host: String): String {
    val clean = host
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
    return when {
        clean.contains("github", ignoreCase = true) -> "GitHub"
        clean.contains("gitlab", ignoreCase = true) -> "GitLab"
        clean.contains("gitee", ignoreCase = true) -> "Gitee"
        clean.contains("bitbucket", ignoreCase = true) -> "Bitbucket"
        clean.contains("codeberg", ignoreCase = true) -> "Codeberg"
        clean.contains("azure", ignoreCase = true) || clean.contains("visualstudio", ignoreCase = true) -> "Azure DevOps"
        else -> clean.substringBefore('.').ifBlank { clean }.replaceFirstChar { it.uppercase() }
    }
}
