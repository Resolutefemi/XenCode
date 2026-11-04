package com.aicode

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.feature.credentials.presentation.CredentialViewModel
import com.aicode.feature.credentials.presentation.component.CredentialScreen
import com.aicode.feature.editor.presentation.CodeEditorScreen
import com.aicode.feature.browser.presentation.BrowserScreen
import com.aicode.feature.browser.presentation.BrowserViewModel
import com.aicode.feature.git.presentation.GitViewModel
import com.aicode.feature.git.presentation.component.GitScreen
import com.aicode.feature.terminal.presentation.TerminalViewModel
import com.aicode.feature.terminal.presentation.component.TerminalScreen

/** Content currently hosted in the wide-screen right workbench pane. */
internal enum class WorkbenchPaneKind { NONE, EDITOR, TERMINAL, GIT, BROWSER }

/**
 * Right-pane content shown next to the chat panel on wide screens.
 *
 * The wrapped pages are standalone "arguments + callbacks" components; this only wires their back
 * action to "close the pane". On narrow screens the same pages still render through fullscreen
 * NavHost routes, so both paths share one UI implementation.
 *
 * ViewModels are scoped to the chat page's NavBackStackEntry, so switching between pane contents
 * does not destroy them; terminal sessions themselves are owned by the singleton
 * TerminalSessionManager, independent of this scope.
 */
@Composable
internal fun WorkbenchPaneContent(
    kind: WorkbenchPaneKind,
    editorPath: String,
    editorLine: Int,
    onClose: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when (kind) {
            WorkbenchPaneKind.NONE -> Unit

            WorkbenchPaneKind.EDITOR -> CodeEditorScreen(
                path = editorPath,
                initialLine = editorLine,
                onBack = onClose,
                embedded = true
                // No fileTreeHost: the pane editor keeps the X close, and the file tree lives
                // in the permanent chat sidebar next to it.
            )

            WorkbenchPaneKind.TERMINAL -> {
                val terminalViewModel: TerminalViewModel = hiltViewModel()
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateBack = onClose,
                    embedded = true
                )
            }

            WorkbenchPaneKind.GIT -> {
                // The credentials page stays as an inner layer of the pane instead of jumping to
                // a fullscreen route, which would suddenly turn the two-column layout into a
                // full-screen takeover.
                var showCredentials by rememberSaveable { mutableStateOf(false) }
                if (showCredentials) {
                    val credentialViewModel: CredentialViewModel = hiltViewModel()
                    CredentialScreen(
                        viewModel = credentialViewModel,
                        onNavigateBack = { showCredentials = false }
                    )
                } else {
                    val gitViewModel: GitViewModel = hiltViewModel()
                    GitScreen(
                        viewModel = gitViewModel,
                        onNavigateToCredentials = { showCredentials = true },
                        onNavigateBack = onClose,
                        onOpenFile = onOpenFile,
                        embedded = true
                    )
                }
            }
            WorkbenchPaneKind.BROWSER -> {
                val browserViewModel: BrowserViewModel = hiltViewModel()
                BrowserScreen(
                    browserManager = browserViewModel.browserManager,
                    onNavigateBack = onClose,
                    embedded = true
                )
            }
        }
    }
}
