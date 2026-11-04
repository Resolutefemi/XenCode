package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.agent.presentation.BrowseClipboard
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeNode
import com.aicode.feature.workspace.domain.isValidFileEntryName
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Download
import compose.icons.feathericons.Link
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import kotlinx.coroutines.delay

/**
 * Workspace file tree: indent-in-place expansion from the workspace root.
 * Rows share one horizontal scroll so deep paths stay readable instead of ellipsizing.
 * Long-press a directory (including the workspace root) to create files or folders.
 */
@Composable
fun FileBrowserTab(
    state: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String?,
    fileOpPaths: Set<String>,
    clipboard: BrowseClipboard?,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onClearClipboard: () -> Unit,
    onImportFiles: (String) -> Unit = {},
    onImportFolder: (String) -> Unit = {},
    onCopyPath: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var creating by remember { mutableStateOf<CreateTarget?>(null) }
    var menuNode by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingRename by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingDelete by remember { mutableStateOf<FileTreeNode?>(null) }
    val hScroll = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            is FileBrowseState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            is FileBrowseState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.detail ?: stringResource(R.string.file_browser_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }

            is FileBrowseState.Success -> {
                val listState = rememberLazyListState()
                val textMeasurer = rememberTextMeasurer()
                val textStyle = MaterialTheme.typography.bodyMedium
                val density = LocalDensity.current
                val maxContentPx = remember(state.nodes, textStyle) {
                    with(density) {
                        val widest = state.nodes.maxByOrNull { node ->
                            val label = if (node.isRoot) WORKSPACE_LABEL else node.entry.name
                            node.depth * 4 + label.sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 }
                        } ?: return@with 0
                        val label = if (widest.isRoot) WORKSPACE_LABEL else widest.entry.name
                        val textW = textMeasurer.measure(label, textStyle).size.width
                        (FILE_TREE_ROW_OVERHEAD.toPx() + (Spacing.lg * widest.depth).toPx() + textW).toInt()
                    }
                }
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val rowWidthPx = maxOf(constraints.maxWidth, maxContentPx)
                    val rowWidth = with(density) { rowWidthPx.toDp() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(hScroll)
                    ) {
                        items(state.nodes, key = { it.path }) { node ->
                            FileTreeRow(
                                node = node,
                                rowWidth = rowWidth,
                                expanding = node.entry.isDirectory && node.path == expandingPath,
                                busy = node.path in fileOpPaths,
                                onClick = {
                                    if (node.entry.isDirectory) onToggleExpand(node.path)
                                    else onOpenFile(node.path)
                                },
                                onLongClick = { menuNode = node },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                    fadeOutSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                    placementSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                                )
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            clipboard?.let { clip ->
                ClipboardIndicator(
                    clipboard = clip,
                    onClear = onClearClipboard,
                    modifier = Modifier.padding(end = Spacing.xs)
                )
            }
            if (fileOpPaths.isNotEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = Spacing.xs).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.file_browser_refresh),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    creating?.let { target ->
        FileNameInputDialog(
            title = stringResource(
                if (target.kind == CreateKind.FILE) R.string.file_browser_new_file
                else R.string.file_browser_new_folder
            ),
            confirmLabel = stringResource(R.string.common_create),
            initialName = "",
            onConfirm = { name ->
                if (target.kind == CreateKind.FILE) onCreateFile(target.parent, name)
                else onCreateFolder(target.parent, name)
                creating = null
            },
            onDismiss = { creating = null }
        )
    }

    menuNode?.let { node ->
        FileTreeActionSheet(
            node = node,
            clipboard = clipboard,
            onNewFile = {
                menuNode = null
                if (!node.isRoot && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FILE)
            },
            onNewFolder = {
                menuNode = null
                if (!node.isRoot && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FOLDER)
            },
            onRename = {
                menuNode = null
                pendingRename = node
            },
            onDelete = {
                menuNode = null
                pendingDelete = node
            },
            onCopy = {
                menuNode = null
                onCopyEntry(node.path, node.entry.name)
            },
            onCut = {
                menuNode = null
                onCutEntry(node.path, node.entry.name)
            },
            onPasteHere = {
                val target = node.path
                menuNode = null
                onPasteEntry(target) { }
            },
            onImportFiles = {
                val target = node.path
                menuNode = null
                if (!node.isRoot && node.path !in expandedPaths) onToggleExpand(node.path)
                onImportFiles(target)
            },
            onImportFolder = {
                val target = node.path
                menuNode = null
                if (!node.isRoot && node.path !in expandedPaths) onToggleExpand(node.path)
                onImportFolder(target)
            },
            onCopyPath = {
                val target = node.path
                menuNode = null
                onCopyPath(target)
            },
            onDismiss = { menuNode = null }
        )
    }

    pendingRename?.let { node ->
        FileNameInputDialog(
            title = stringResource(R.string.common_rename),
            confirmLabel = stringResource(R.string.common_rename),
            initialName = node.entry.name,
            onConfirm = { name ->
                onRenameEntry(node.path, name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = {
                Text(
                    stringResource(
                        if (node.entry.isDirectory) {
                            R.string.file_browser_delete_folder_confirm
                        } else {
                            R.string.file_browser_delete_file_confirm
                        },
                        node.entry.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry(node.path)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

private enum class CreateKind { FILE, FOLDER }

private data class CreateTarget(val parent: String, val kind: CreateKind)

@Composable
private fun FileNameInputDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = stringResource(R.string.file_browser_name_label),
                modifier = Modifier.fillMaxWidth(),
                colors = dialogTextFieldColors()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = isValidFileEntryName(name) && name.trim() != initialName
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun ClipboardIndicator(
    clipboard: BrowseClipboard,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClear,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = if (clipboard.isCut) Icons.Filled.ContentCut else FeatherIcons.Copy,
            contentDescription = stringResource(
                if (clipboard.isCut) R.string.file_browser_clipboard_cut
                else R.string.file_browser_clipboard_copy
            ),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTreeActionSheet(
    node: FileTreeNode,
    clipboard: BrowseClipboard?,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPasteHere: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onCopyPath: () -> Unit,
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
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = if (node.isRoot) WORKSPACE_LABEL else node.entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            if (clipboard != null && node.entry.isDirectory) {
                FileTreeSheetActionRow(
                    icon = FeatherIcons.Clipboard,
                    label = stringResource(R.string.file_browser_paste_here),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onPasteHere
                )
            }
            if (node.entry.isDirectory) {
                FileTreeSheetActionRow(
                    icon = FeatherIcons.FilePlus,
                    label = stringResource(R.string.file_browser_new_file),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFile
                )
                FileTreeSheetActionRow(
                    icon = FeatherIcons.FolderPlus,
                    label = stringResource(R.string.file_browser_new_folder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFolder
                )
                FileTreeSheetActionRow(
                    icon = FeatherIcons.Upload,
                    label = stringResource(R.string.file_browser_import_files),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onImportFiles
                )
                FileTreeSheetActionRow(
                    icon = FeatherIcons.Download,
                    label = stringResource(R.string.file_browser_import_folder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onImportFolder
                )
            }
            FileTreeSheetActionRow(
                icon = FeatherIcons.Link,
                label = stringResource(R.string.file_browser_copy_path),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onCopyPath
            )
            if (!node.isRoot) {
                FileTreeSheetActionRow(
                    icon = FeatherIcons.Copy,
                    label = stringResource(R.string.common_copy),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCopy
                )
                FileTreeSheetActionRow(
                    icon = Icons.Filled.ContentCut,
                    label = stringResource(R.string.common_cut),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCut
                )
                FileTreeSheetActionRow(
                    icon = FeatherIcons.Edit2,
                    label = stringResource(R.string.common_rename),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onRename
                )
                FileTreeSheetActionRow(
                    icon = FeatherIcons.Trash2,
                    label = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun FileTreeSheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
            Spacer(Modifier.width(Spacing.lg))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileTreeNode,
    rowWidth: Dp,
    expanding: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showExpanding by remember { mutableStateOf(false) }
    LaunchedEffect(expanding) {
        if (expanding) {
            delay(180)
            showExpanding = true
        } else {
            showExpanding = false
        }
    }
    val isDotfile = !node.isRoot && node.entry.name.startsWith(".")
    val decorationColor: Color? = when {
        node.hasError -> MaterialTheme.colorScheme.error
        node.ignored -> FileTreeIgnoredColor
        isDotfile -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> null
    }
    val rowShape = RoundedCornerShape(Radius.md)
    Row(
        modifier = modifier
            .width(rowWidth)
            .clip(rowShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Spacing.xs + 2.dp)
            .padding(start = Spacing.sm, end = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(Spacing.lg * node.depth))
        if (node.entry.isDirectory) {
            if (showExpanding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                ExpandableChevronIcon(
                    expanded = node.isExpanded,
                    style = ChevronRotationStyle.RIGHT_DOWN,
                    contentDescription = stringResource(
                        if (node.isExpanded) R.string.file_browser_collapse else R.string.file_browser_expand
                    ),
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.width(Spacing.xs))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            FileTreeIcon(node = node, decorationColor = decorationColor)
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = if (node.isRoot) WORKSPACE_LABEL else node.entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = decorationColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun FileTreeIcon(node: FileTreeNode, decorationColor: Color?) {
    val vector = if (node.entry.isDirectory) {
        FeatherIcons.Folder
    } else {
        fileTypeIconFor(node.entry.name)
    }
    Icon(
        imageVector = vector,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = decorationColor
            ?: if (node.entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private val FileTreeIgnoredColor = Color(0xFFCC8844)

internal const val WORKSPACE_LABEL = "workspace"

private val FILE_TREE_ROW_OVERHEAD = 68.dp
