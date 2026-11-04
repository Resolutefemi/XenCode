package com.aicode.feature.network.presentation

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.feature.network.domain.HttpMethod
import com.aicode.feature.network.domain.JsonViewMode
import com.aicode.feature.network.domain.NetworkHeader
import com.aicode.feature.network.domain.NetworkHistoryEntry
import com.aicode.feature.network.domain.NetworkMetrics
import com.aicode.feature.network.domain.NetworkPane
import com.aicode.feature.network.domain.NetworkSendState
import com.aicode.feature.network.domain.RequestTab
import com.aicode.feature.network.domain.ResponseTab
import com.aicode.feature.network.domain.ResponseViewMode
import com.aicode.feature.network.domain.bodyToTableRows
import com.aicode.feature.network.domain.editorDisplayText
import com.aicode.feature.network.domain.headersToJson
import com.aicode.feature.network.domain.jsonToTreeNodes
import com.aicode.feature.network.domain.prettyPrintJson
import com.aicode.feature.network.domain.safeJsonViewMode
import com.aicode.feature.network.domain.safeResponseViewMode
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.FileText
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Send
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkRequestScreen(
    viewModel: NetworkRequestViewModel,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { NetworkPane.entries.size }
    val requestScroll = rememberScrollState()
    val responseScroll = rememberScrollState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showImportCurl by remember { mutableStateOf(false) }
    var showExportCurl by remember { mutableStateOf(false) }

    val tabsScrolling by remember {
        derivedStateOf { requestScroll.isScrollInProgress || responseScroll.isScrollInProgress }
    }

    LaunchedEffect(state.sendState) {
        if (state.sendState is NetworkSendState.Success && pagerState.currentPage != NetworkPane.RESPONSE.ordinal) {
            pagerState.animateScrollToPage(NetworkPane.RESPONSE.ordinal)
        }
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_request_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            if (embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(
                                if (embedded) R.string.common_close else R.string.common_back
                            )
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                FeatherIcons.MoreHorizontal,
                                contentDescription = stringResource(R.string.network_request_more)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.network_request_history)) },
                                leadingIcon = {
                                    Icon(
                                        FeatherIcons.Clock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.refreshHistory()
                                    showHistory = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.network_request_import_curl)) },
                                leadingIcon = {
                                    Icon(
                                        FeatherIcons.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showImportCurl = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.network_request_export_curl)) },
                                leadingIcon = {
                                    Icon(
                                        FeatherIcons.Upload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showExportCurl = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_clear)) },
                                leadingIcon = {
                                    Icon(
                                        FeatherIcons.Trash2,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.clear()
                                }
                            )
                        }
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
            ) { page ->
                when (NetworkPane.entries[page]) {
                    NetworkPane.REQUEST -> RequestPane(
                        url = state.url,
                        method = state.method,
                        queryJson = state.queryJson,
                        headersJson = state.headersJson,
                        bodyJson = state.bodyJson,
                        authJson = state.authJson,
                        followRedirects = state.settings.followRedirects,
                        sslVerify = state.settings.sslVerify,
                        timeoutSeconds = state.settings.timeoutSeconds,
                        sendState = state.sendState,
                        scrollState = requestScroll,
                        onUrlChange = viewModel::updateUrl,
                        onSelectMethod = viewModel::selectMethod,
                        onQueryChange = viewModel::updateQueryJson,
                        onHeadersChange = viewModel::updateHeadersJson,
                        onBodyChange = viewModel::updateBodyJson,
                        onAuthChange = viewModel::updateAuthJson,
                        onFollowRedirects = viewModel::updateFollowRedirects,
                        onSslVerify = viewModel::updateSslVerify,
                        onTimeoutChange = viewModel::updateTimeout,
                        onSend = viewModel::send
                    )
                    NetworkPane.RESPONSE -> ResponsePane(
                        sendState = state.sendState,
                        scrollState = responseScroll
                    )
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Send, stringResource(R.string.network_request_tab_request)),
                    FloatingTabItem(FeatherIcons.FileText, stringResource(R.string.network_request_tab_response))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showHistory) {
        HistorySheet(
            entries = state.history,
            selectedId = state.selectedHistoryId,
            onSelected = { entry ->
                viewModel.loadHistory(entry)
                showHistory = false
            },
            onDismiss = { showHistory = false }
        )
    }

    if (showImportCurl) {
        CurlCommandSheet(
            title = stringResource(R.string.network_request_import_curl),
            actionLabel = stringResource(R.string.network_request_import_curl),
            initialValue = "",
            readOnly = false,
            errorText = stringResource(R.string.network_request_curl_invalid),
            onAction = { raw ->
                val ok = viewModel.importCurl(raw)
                if (ok) showImportCurl = false
                ok
            },
            onDismiss = { showImportCurl = false }
        )
    }

    if (showExportCurl) {
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val exported = remember { viewModel.exportCurl() }
        CurlCommandSheet(
            title = stringResource(R.string.network_request_export_curl),
            actionLabel = stringResource(R.string.network_request_export_curl),
            initialValue = exported,
            readOnly = true,
            errorText = null,
            onAction = { text ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("curl", text)))
                }
                showExportCurl = false
                true
            },
            onDismiss = { showExportCurl = false }
        )
    }
}

@Composable
private fun RequestPane(
    url: String,
    method: HttpMethod,
    queryJson: String,
    headersJson: String,
    bodyJson: String,
    authJson: String,
    followRedirects: Boolean,
    sslVerify: Boolean,
    timeoutSeconds: Int,
    sendState: NetworkSendState,
    scrollState: androidx.compose.foundation.ScrollState,
    onUrlChange: (String) -> Unit,
    onSelectMethod: (HttpMethod) -> Unit,
    onQueryChange: (String) -> Unit,
    onHeadersChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onAuthChange: (String) -> Unit,
    onFollowRedirects: (Boolean) -> Unit,
    onSslVerify: (Boolean) -> Unit,
    onTimeoutChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val sending = sendState is NetworkSendState.Sending
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var queryMode by rememberSaveable { mutableStateOf(JsonViewMode.JSON.name) }
    var headersMode by rememberSaveable { mutableStateOf(JsonViewMode.JSON.name) }
    var bodyMode by rememberSaveable { mutableStateOf(JsonViewMode.JSON.name) }
    var authMode by rememberSaveable { mutableStateOf(JsonViewMode.JSON.name) }
    var showModeSheet by remember { mutableStateOf(false) }
    val tab = RequestTab.entries[selectedTab.coerceIn(0, RequestTab.entries.lastIndex)]
    val viewMode = when (tab) {
        RequestTab.QUERY -> safeJsonViewMode(queryMode)
        RequestTab.HEADERS -> safeJsonViewMode(headersMode)
        RequestTab.BODY -> safeJsonViewMode(bodyMode)
        RequestTab.AUTHORIZATION -> safeJsonViewMode(authMode)
        RequestTab.SETTINGS -> null
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(bottom = 96.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            AppTextField(
                value = url,
                onValueChange = onUrlChange,
                label = stringResource(R.string.network_request_url_label),
                placeholder = stringResource(R.string.network_request_url_hint),
                singleLine = true,
                enabled = !sending,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        onSend()
                    }
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                HttpMethod.entries.forEach { item ->
                    val selected = item == method
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectMethod(item) },
                        enabled = !sending,
                        label = { Text(item.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.semanticColors.cardSurface,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = MaterialTheme.semanticColors.subtleBorder,
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSend()
                },
                enabled = !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(Radius.mdLarge),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = 0.dp)
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(stringResource(R.string.network_request_send))
            }

            if (sendState is NetworkSendState.Failure) {
                Text(
                    text = sendState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        NetworkScrollableTabs(
            labels = listOf(
                stringResource(R.string.network_request_tab_query),
                stringResource(R.string.network_request_tab_headers),
                stringResource(R.string.network_request_tab_body),
                stringResource(R.string.network_request_tab_authorization),
                stringResource(R.string.network_request_tab_settings)
            ),
            selected = selectedTab,
            onSelect = { selectedTab = it }
        )

        if (viewMode != null) {
            ViewModeDropdown(
                label = jsonViewModeLabel(viewMode),
                onClick = { showModeSheet = true },
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        } else {
            Spacer(Modifier.height(Spacing.sm))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            if (tab == RequestTab.SETTINGS) {
                RequestSettings(
                    followRedirects = followRedirects,
                    sslVerify = sslVerify,
                    timeoutSeconds = timeoutSeconds,
                    enabled = !sending,
                    onFollowRedirects = onFollowRedirects,
                    onSslVerify = onSslVerify,
                    onTimeoutChange = onTimeoutChange
                )
            } else {
                val jsonText = when (tab) {
                    RequestTab.QUERY -> queryJson
                    RequestTab.HEADERS -> headersJson
                    RequestTab.BODY -> bodyJson
                    else -> authJson
                }
                val onJsonChange = when (tab) {
                    RequestTab.QUERY -> onQueryChange
                    RequestTab.HEADERS -> onHeadersChange
                    RequestTab.BODY -> onBodyChange
                    else -> onAuthChange
                }
                val emptyText = when (tab) {
                    RequestTab.QUERY -> stringResource(R.string.network_request_query_empty)
                    RequestTab.HEADERS -> stringResource(R.string.network_request_headers_empty_request)
                    RequestTab.BODY -> stringResource(R.string.network_request_body_empty_request)
                    else -> stringResource(R.string.network_request_auth_empty)
                }
                JsonSection(
                    jsonText = jsonText,
                    mode = viewMode ?: JsonViewMode.JSON,
                    editable = !sending,
                    onJsonChange = onJsonChange,
                    emptyText = emptyText
                )
            }
        }
    }

    if (showModeSheet && viewMode != null) {
        JsonViewModeSheet(
            selected = viewMode,
            onSelected = { mode ->
                when (tab) {
                    RequestTab.QUERY -> queryMode = mode.name
                    RequestTab.HEADERS -> headersMode = mode.name
                    RequestTab.BODY -> bodyMode = mode.name
                    RequestTab.AUTHORIZATION -> authMode = mode.name
                    RequestTab.SETTINGS -> Unit
                }
                showModeSheet = false
            },
            onDismiss = { showModeSheet = false }
        )
    }
}

@Composable
private fun NetworkScrollableTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val scroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                val selectedTab = index == selected
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable { onSelect(index) }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedTab) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selectedTab) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Box(
                        modifier = Modifier
                            .height(2.dp)
                            .width(24.dp)
                            .background(
                                color = if (selectedTab) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun JsonSection(
    jsonText: String,
    mode: JsonViewMode,
    editable: Boolean,
    onJsonChange: (String) -> Unit,
    emptyText: String
) {
    val shape = RoundedCornerShape(Radius.lg)
    val editorMinHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp.coerceAtLeast(280.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.semanticColors.cardSurface,
        border = BorderStroke(0.5.dp, MaterialTheme.semanticColors.subtleBorder)
    ) {
        when (mode) {
            JsonViewMode.JSON -> {
                JsonCodeEditor(
                    value = editorDisplayText(jsonText),
                    onValueChange = if (editable) onJsonChange else null,
                    readOnly = !editable,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = editorMinHeight)
                        .height(editorMinHeight)
                )
            }
            JsonViewMode.TREE -> {
                val nodes = remember(jsonText) {
                    runCatching { jsonToTreeNodes(jsonText) }.getOrNull()
                }
                JsonTreeView(
                    nodes = nodes,
                    emptyText = emptyText,
                    invalidText = stringResource(R.string.network_request_json_invalid),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            JsonViewMode.TABLE -> {
                TableInputEditor(
                    jsonText = jsonText,
                    onJsonChange = onJsonChange,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun RequestSettings(
    followRedirects: Boolean,
    sslVerify: Boolean,
    timeoutSeconds: Int,
    enabled: Boolean,
    onFollowRedirects: (Boolean) -> Unit,
    onSslVerify: (Boolean) -> Unit,
    onTimeoutChange: (String) -> Unit
) {
    var timeoutText by remember(timeoutSeconds) { mutableStateOf(timeoutSeconds.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.network_request_setting_redirects),
                subtitle = stringResource(R.string.network_request_setting_redirects_desc),
                trailing = {
                    AppSwitch(
                        checked = followRedirects,
                        onCheckedChange = if (enabled) onFollowRedirects else null,
                        enabled = enabled
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.network_request_setting_ssl),
                subtitle = stringResource(R.string.network_request_setting_ssl_desc),
                trailing = {
                    AppSwitch(
                        checked = sslVerify,
                        onCheckedChange = if (enabled) onSslVerify else null,
                        enabled = enabled
                    )
                }
            )
        }
        AppTextField(
            value = timeoutText,
            onValueChange = { value ->
                timeoutText = value.filter { it.isDigit() }.take(3)
                onTimeoutChange(timeoutText)
            },
            label = stringResource(R.string.network_request_setting_timeout),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            )
        )
    }
}

@Composable
private fun ResponsePane(
    sendState: NetworkSendState,
    scrollState: androidx.compose.foundation.ScrollState
) {
    val exchange = when (sendState) {
        is NetworkSendState.Success -> sendState.exchange
        is NetworkSendState.Failure -> sendState.exchange
        else -> null
    }
    if (exchange == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = 88.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.network_request_response_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var headersMode by rememberSaveable { mutableStateOf(ResponseViewMode.TABLE.name) }
    var bodyMode by rememberSaveable { mutableStateOf(ResponseViewMode.JSON.name) }
    var showModeSheet by remember { mutableStateOf(false) }
    val tab = ResponseTab.entries[selectedTab.coerceIn(0, ResponseTab.entries.lastIndex)]
    val viewMode = when (tab) {
        ResponseTab.HEADERS -> safeResponseViewMode(headersMode)
        ResponseTab.BODY -> safeResponseViewMode(bodyMode)
        ResponseTab.METRICS -> null
    }
    val fillBodyJson = tab == ResponseTab.BODY && viewMode == ResponseViewMode.JSON

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (fillBodyJson) Modifier else Modifier.verticalScroll(scrollState))
            .padding(bottom = if (fillBodyJson) 0.dp else 88.dp)
    ) {
        NetworkScrollableTabs(
            labels = listOf(
                stringResource(R.string.network_request_headers_count, exchange.headers.size),
                stringResource(R.string.network_request_tab_body),
                stringResource(R.string.network_request_tab_metrics)
            ),
            selected = selectedTab,
            onSelect = { selectedTab = it }
        )

        if (viewMode != null) {
            ViewModeDropdown(
                label = responseViewModeLabel(viewMode),
                onClick = { showModeSheet = true },
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        } else {
            Spacer(Modifier.height(Spacing.sm))
        }

        Box(
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .then(if (fillBodyJson) Modifier.weight(1f, fill = true) else Modifier)
        ) {
            when (tab) {
                ResponseTab.HEADERS -> {
                    val headersJson = remember(exchange.headers) {
                        runCatching { headersToJson(exchange.headers) }.getOrDefault("{}")
                    }
                    ResponseContentBox(
                        mode = viewMode ?: ResponseViewMode.TABLE,
                        tableRows = exchange.headers,
                        jsonText = headersJson,
                        emptyText = stringResource(R.string.network_request_headers_empty)
                    )
                }
                ResponseTab.BODY -> {
                    val tableRows = remember(exchange.body) {
                        runCatching { bodyToTableRows(exchange.body) }.getOrDefault(emptyList())
                    }
                    val bodyJson = remember(exchange.body) {
                        runCatching { prettyPrintJson(exchange.body) }.getOrDefault(exchange.body)
                    }
                    ResponseContentBox(
                        mode = viewMode ?: ResponseViewMode.JSON,
                        tableRows = tableRows,
                        jsonText = bodyJson,
                        emptyText = stringResource(R.string.network_request_body_empty),
                        expandJson = true
                    )
                }
                ResponseTab.METRICS -> MetricsList(exchange.metrics)
            }
        }

    }

    if (showModeSheet && viewMode != null) {
        ViewModeSheet(
            selected = viewMode,
            onSelected = { mode ->
                when (tab) {
                    ResponseTab.HEADERS -> headersMode = mode.name
                    ResponseTab.BODY -> bodyMode = mode.name
                    ResponseTab.METRICS -> Unit
                }
                showModeSheet = false
            },
            onDismiss = { showModeSheet = false }
        )
    }
}



@Composable
private fun ViewModeDropdown(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
            imageVector = FeatherIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewModeSheet(
    selected: ResponseViewMode,
    onSelected: (ResponseViewMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.network_request_view_mode),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            ResponseViewMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Surface(
                    onClick = { onSelected(mode) },
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = responseViewModeLabel(mode),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JsonViewModeSheet(
    selected: JsonViewMode,
    onSelected: (JsonViewMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.network_request_view_mode),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            JsonViewMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Surface(
                    onClick = { onSelected(mode) },
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = jsonViewModeLabel(mode),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun jsonViewModeLabel(mode: JsonViewMode): String = stringResource(
    when (mode) {
        JsonViewMode.JSON -> R.string.network_request_view_json
        JsonViewMode.TREE -> R.string.network_request_view_tree
        JsonViewMode.TABLE -> R.string.network_request_view_tabled_input
    }
)

@Composable
private fun responseViewModeLabel(mode: ResponseViewMode): String = stringResource(
    when (mode) {
        ResponseViewMode.TABLE -> R.string.network_request_view_table
        ResponseViewMode.JSON -> R.string.network_request_view_json
    }
)

@Composable
private fun ResponseContentBox(
    mode: ResponseViewMode,
    tableRows: List<NetworkHeader>,
    jsonText: String,
    emptyText: String,
    expandJson: Boolean = false
) {
    val shape = RoundedCornerShape(Radius.lg)
    val jsonMinHeight = if (expandJson) {
        (LocalConfiguration.current.screenHeightDp * 0.62f).dp.coerceAtLeast(360.dp)
    } else {
        280.dp
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expandJson && mode == ResponseViewMode.JSON) Modifier.fillMaxHeight() else Modifier),
        shape = shape,
        color = MaterialTheme.semanticColors.cardSurface,
        border = BorderStroke(0.5.dp, MaterialTheme.semanticColors.subtleBorder)
    ) {
        when (mode) {
            ResponseViewMode.TABLE -> {
                if (tableRows.isEmpty()) {
                    EmptyBoxText(emptyText)
                } else {
                    HeaderTable(tableRows)
                }
            }
            ResponseViewMode.JSON -> {
                if (jsonText.isBlank()) {
                    EmptyBoxText(emptyText)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (expandJson) Modifier.fillMaxHeight() else Modifier)
                    ) {
                        JsonCodeEditor(
                            value = editorDisplayText(jsonText),
                            onValueChange = null,
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (expandJson) {
                                        Modifier.fillMaxHeight()
                                    } else {
                                        Modifier.height(jsonMinHeight)
                                    }
                                )
                                .padding(top = 36.dp)
                        )
                        CopyJsonButton(
                            text = jsonText,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(Spacing.xs)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyJsonButton(
    text: String,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("json", text)))
                copied = true
            }
        },
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            contentDescription = stringResource(R.string.common_copy),
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    entries: List<NetworkHistoryEntry>,
    selectedId: String?,
    onSelected: (NetworkHistoryEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val formatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.network_request_history),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.network_request_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        val selected = entry.id == selectedId
                        Surface(
                            onClick = { onSelected(entry) },
                            color = androidx.compose.ui.graphics.Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${entry.method}  ${entry.url}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = formatter.format(Date(entry.createdAt)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = FeatherIcons.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyBoxText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Spacing.lg)
    )
}

@Composable
private fun HeaderTable(rows: List<NetworkHeader>) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        TableRow(
            left = stringResource(R.string.network_request_table_name),
            right = stringResource(R.string.network_request_table_value),
            header = true
        )
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.semanticColors.subtleBorder
        )
        rows.forEachIndexed { index, row ->
            TableRow(left = row.name, right = row.value, header = false)
            if (index != rows.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.semanticColors.subtleBorder.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun TableRow(left: String, right: String, header: Boolean) {
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = left,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Medium,
            color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(140.dp),
            maxLines = if (header) 1 else 4,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = right,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (header) FontFamily.Default else FontFamily.Monospace
            ),
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 180.dp),
            maxLines = if (header) 1 else 8,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetricsList(metrics: NetworkMetrics) {
    SettingsGroup {
        MetricRow(stringResource(R.string.network_request_metric_id), metrics.requestId.ifBlank { "—" })
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_method), metrics.method.ifBlank { "—" })
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_url), metrics.url.ifBlank { "—" })
        SettingsDivider()
        MetricRow(
            stringResource(R.string.network_request_metric_status),
            if (metrics.statusCode > 0) "${metrics.statusCode} ${metrics.statusText}".trim() else "—"
        )
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_protocol), metrics.protocol.ifBlank { "—" })
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_remote), metrics.remoteAddress.ifBlank { "—" })
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_duration), formatMs(metrics.durationMs))
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_dns), formatOptionalMs(metrics.dnsMs))
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_connect), formatOptionalMs(metrics.connectMs))
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_tls), formatOptionalMs(metrics.tlsMs))
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_request_size), formatBytes(metrics.requestBytes))
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_response_size), formatBytes(metrics.responseBytes))
        SettingsDivider()
        MetricRow(stringResource(R.string.network_request_metric_content_type), metrics.contentType.ifBlank { "—" })
    }
}

@Composable
private fun MetricRow(title: String, value: String) {
    SettingsRow(
        title = title,
        subtitle = value
    )
}

private fun formatMs(value: Long): String = "${value} ms"

private fun formatOptionalMs(value: Long): String = if (value < 0) "—" else formatMs(value)

private fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", value / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
}
