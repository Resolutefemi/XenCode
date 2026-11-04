package com.aicode.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Users
import compose.icons.feathericons.WifiOff
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.settings.data.remote.MarketplaceCategory
import com.aicode.feature.settings.presentation.MarketplaceDetailUi
import com.aicode.feature.settings.presentation.MarketplaceInstallFailure
import com.aicode.feature.settings.presentation.MarketplaceRow
import com.aicode.feature.settings.presentation.MarketplaceType
import com.aicode.feature.settings.presentation.MarketplaceUiState
import com.aicode.feature.settings.presentation.MarketplaceViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Marketplace section page for one collection type: category filter chips
 * (skills only) on top, then the centered skeleton or the list. Tapping a row
 * opens the detail bottom sheet with a one-tap install; the list auto-pages
 * when scrolled near the bottom.
 */
@Composable
internal fun MarketplaceSection(
    viewModel: MarketplaceViewModel,
    onInstalled: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Install success: toast + close the sheet + notify SettingsScreen to rescan.
    LaunchedEffect(state.lastInstalledName) {
        state.lastInstalledName?.let {
            Toast.makeText(context, context.getString(installedRes(state.type)), Toast.LENGTH_SHORT).show()
            viewModel.clearInstallFeedback()
            viewModel.dismissDetail()
            onInstalled()
        }
    }

    // Install failure: map the reason to a message, then clear one-shot feedback.
    LaunchedEffect(state.installFailure) {
        state.installFailure?.let { failure ->
            val reasonRes = when (failure) {
                MarketplaceInstallFailure.NETWORK -> R.string.skills_market_error_network
                MarketplaceInstallFailure.SERVER -> R.string.skills_market_error_server
                MarketplaceInstallFailure.INVALID_NAME -> R.string.skills_import_error_invalid_name
                MarketplaceInstallFailure.NAME_CONFLICT -> R.string.skills_import_error_name_conflict
                MarketplaceInstallFailure.EMPTY_CONTENT -> R.string.skills_import_error_empty_content
                MarketplaceInstallFailure.NO_SLOT -> R.string.prompts_market_error_no_slot
                MarketplaceInstallFailure.IO -> R.string.skills_import_error_io
            }
            val message = context.getString(R.string.skills_market_install_failed, context.getString(reasonRes))
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearInstallFeedback()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.type == MarketplaceType.SKILLS &&
            !state.loading &&
            !state.loadFailed &&
            state.categories.isNotEmpty()
        ) {
            CategoryChipRow(
                categories = state.categories,
                selectedCategoryId = state.selectedCategoryId,
                onSelect = viewModel::selectCategory
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.loadFailed -> LoadFailedPane(onRetry = viewModel::reload)
                else -> ListPane(
                    state = state,
                    viewModel = viewModel
                )
            }
        }
    }

    if (state.detailOpen) {
        DetailSheet(
            state = state,
            onRetryDetail = viewModel::retryDetail,
            onInstall = viewModel::install,
            onDismiss = viewModel::dismissDetail
        )
    }
}

private fun installedRes(type: MarketplaceType): Int = when (type) {
    MarketplaceType.SKILLS -> R.string.skills_market_installed
    MarketplaceType.PROMPTS -> R.string.prompts_market_installed
    MarketplaceType.SUB_AGENTS -> R.string.subagents_market_installed
}

/** Row icon per collection type. */
private fun rowIcon(type: MarketplaceType): ImageVector = when (type) {
    MarketplaceType.SKILLS -> FeatherIcons.Book
    MarketplaceType.PROMPTS -> FeatherIcons.FileText
    MarketplaceType.SUB_AGENTS -> FeatherIcons.Users
}

/** Top category filter: "All" plus server categories, horizontally scrollable. Skills only. */
@Composable
private fun CategoryChipRow(
    categories: List<MarketplaceCategory>,
    selectedCategoryId: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item {
            FilterChip(
                selected = selectedCategoryId == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.skills_market_all)) }
            )
        }
        items(categories, key = { it.id }) { category ->
            FilterChip(
                selected = selectedCategoryId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text(category.name) }
            )
        }
    }
}

/** List body: empty state / auto-paging item list. */
@Composable
private fun ListPane(
    state: MarketplaceUiState,
    viewModel: MarketplaceViewModel
) {
    if (state.rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(emptyRes(state.type)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.xl)
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // Infinite scroll: fetch the next page once the last visible item nears the end.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                val current = viewModel.state.value
                if (
                    info.totalItemsCount > 0 &&
                    lastVisible >= info.totalItemsCount - 3 &&
                    current.page < current.totalPages &&
                    !current.loading &&
                    !current.appendingPage
                ) {
                    viewModel.loadNextPage()
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        itemsIndexed(state.rows, key = { _, item -> item.id }) { _, row ->
            MarketplaceItemRow(
                row = row,
                icon = rowIcon(state.type),
                onClick = { viewModel.openDetail(row) }
            )
        }
        if (state.appendingPage) {
            item(key = "appending") {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

private fun emptyRes(type: MarketplaceType): Int = when (type) {
    MarketplaceType.SKILLS -> R.string.skills_market_empty_category
    MarketplaceType.PROMPTS -> R.string.prompts_market_empty
    MarketplaceType.SUB_AGENTS -> R.string.subagents_market_empty
}

/** One marketplace row: icon + name/description + chevron, styled like the local lists. */
@Composable
private fun MarketplaceItemRow(
    row: MarketplaceRow,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.semanticColors.cardSurface)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(start = Spacing.md, end = Spacing.md, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Full-page load failure: offline icon + message + retry button. */
@Composable
private fun LoadFailedPane(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(
                imageVector = FeatherIcons.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = stringResource(R.string.skills_market_error_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.skills_market_retry))
            }
        }
    }
}

/** Detail bottom sheet: meta line + description + install button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(
    state: MarketplaceUiState,
    onRetryDetail: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            when {
                state.detailLoading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                state.detailError || state.detail == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg)
                ) {
                    Text(
                        text = stringResource(detailErrorRes(state.type)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onRetryDetail) {
                        Text(stringResource(R.string.skills_market_retry))
                    }
                }
                else -> {
                    val detail = state.detail
                    if (detail != null) {
                        DetailContent(
                            detail = detail,
                            type = state.type,
                            installing = state.installingId != null,
                            onInstall = onInstall
                        )
                    }
                }
            }
        }
    }
}

private fun detailErrorRes(type: MarketplaceType): Int = when (type) {
    MarketplaceType.SKILLS -> R.string.skills_market_error_detail
    MarketplaceType.PROMPTS -> R.string.prompts_market_error_detail
    MarketplaceType.SUB_AGENTS -> R.string.subagents_market_error_detail
}

private fun scopeNoteRes(type: MarketplaceType): Int = when (type) {
    MarketplaceType.SKILLS -> R.string.skills_market_scope_note
    MarketplaceType.PROMPTS -> R.string.prompts_market_scope_note
    MarketplaceType.SUB_AGENTS -> R.string.subagents_market_scope_note
}

@Composable
private fun DetailContent(
    detail: MarketplaceDetailUi,
    type: MarketplaceType,
    installing: Boolean,
    onInstall: () -> Unit
) {
    Text(
        text = detail.name,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )

    val metaParts = buildList {
        detail.creator?.let { add(it) }
        formatPublishedDate(detail.publishedAt)?.let { add(it) }
        detail.version?.let { add("v" + it) }
    }
    if (metaParts.isNotEmpty()) {
        Text(
            text = metaParts.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (detail.description.isNotBlank()) {
        Text(
            text = detail.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    // Sub-agents show their behavior fields (mode / effort / inject) as one extra line.
    if (!detail.metaLine.isNullOrBlank()) {
        Text(
            text = detail.metaLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Text(
        text = stringResource(scopeNoteRes(type)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
        onClick = onInstall,
        enabled = !installing,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (installing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(stringResource(R.string.skills_market_installing))
        } else {
            Text(stringResource(R.string.skills_market_install))
        }
    }
}

/** ISO timestamp to localized date; null (line hidden) when parsing fails. */
private fun formatPublishedDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        Instant.parse(raw)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }.getOrNull()
}
