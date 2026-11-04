package com.aicode.feature.editor.presentation

import androidx.compose.runtime.Immutable
import com.aicode.feature.agent.presentation.BrowseClipboard
import com.aicode.feature.agent.presentation.FileBrowseState

/** File-tree data and actions shared with the editor's standard navigation drawer. */
@Immutable
data class EditorFileTreeHost(
    val browseState: FileBrowseState,
    val expandedPaths: Set<String>,
    val expandingPath: String?,
    val fileOpPaths: Set<String>,
    val clipboard: BrowseClipboard?,
    val pasteConflict: Pair<String, String>?,
    val onToggleExpand: (String) -> Unit,
    val onOpenFile: (String) -> Unit,
    val onRefreshBrowse: () -> Unit,
    val onCreateFile: (String, String) -> Unit,
    val onCreateFolder: (String, String) -> Unit,
    val onRenameEntry: (String, String) -> Unit,
    val onDeleteEntry: (String) -> Unit,
    val onCopyEntry: (String, String) -> Unit,
    val onCutEntry: (String, String) -> Unit,
    val onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    val onPasteOverwrite: () -> Unit,
    val onCancelPasteOverwrite: () -> Unit,
    val onClearClipboard: () -> Unit,
    val onImportFiles: (String) -> Unit,
    val onImportFolder: (String) -> Unit,
    val onCopyPath: (String) -> Unit
)
