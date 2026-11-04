package com.aicode.feature.editor.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.BrowseClipboard
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.component.FileBrowserTab

/** Standard-drawer body: project file tree without the chat-session tabs. */
@Composable
fun EditorFilesDrawer(
    browseState: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String?,
    fileOpPaths: Set<String>,
    clipboard: BrowseClipboard?,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefreshBrowse: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onClearClipboard: () -> Unit,
    onImportFiles: (String) -> Unit,
    onImportFolder: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
    ) {
        Text(
            text = stringResource(R.string.editor_project_files_tree),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        )
        FileBrowserTab(
            state = browseState,
            expandedPaths = expandedPaths,
            expandingPath = expandingPath,
            fileOpPaths = fileOpPaths,
            clipboard = clipboard,
            onToggleExpand = onToggleExpand,
            onOpenFile = onOpenFile,
            onRefresh = onRefreshBrowse,
            onCreateFile = onCreateFile,
            onCreateFolder = onCreateFolder,
            onRenameEntry = onRenameEntry,
            onDeleteEntry = onDeleteEntry,
            onCopyEntry = onCopyEntry,
            onCutEntry = onCutEntry,
            onPasteEntry = onPasteEntry,
            onClearClipboard = onClearClipboard,
            onImportFiles = onImportFiles,
            onImportFolder = onImportFolder,
            onCopyPath = onCopyPath,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.xs)
        )
    }
}
