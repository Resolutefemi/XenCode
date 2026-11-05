package com.xencode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.feature.agent.domain.prompt.PromptFragment
import com.xencode.feature.agent.domain.prompt.PromptFragmentCatalog
import com.xencode.feature.agent.domain.prompt.PromptFragmentSource
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Prompts page state: the finally effective fragments listed by number (with sources), plus the built-in toggle. */
data class PromptsUiState(
    val fragments: List<PromptFragment> = emptyList(),
    val builtinDisabled: Boolean = false,
    /** With no workspace selected the project layer is not writable; editing/creating falls back to the global layer. */
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
)

/**
 * Prompts page state: the effective fragment list from four source levels (project > global > local > built-in) + the built-in toggle.
 *
 * Both reads and writes are disk IO, uniformly on the IO dispatcher.
 */
@HiltViewModel
class PromptsViewModel @Inject constructor(
    private val catalog: PromptFragmentCatalog,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsUiState())
    val state: StateFlow<PromptsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                PromptsUiState(
                    fragments = catalog.list(projectRoot),
                    builtinDisabled = catalog.isBuiltinDisabled(),
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    /** Saves an override for a number (create and edit share one entry): written to the selected scope layer; when editing changes the number, the old one is cleared. */
    fun saveFragment(
        number: Int,
        title: String,
        scope: PromptFragmentSource,
        content: String,
        previousNumber: Int? = null
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                catalog.saveOverride(
                    number,
                    title,
                    content,
                    projectRoot,
                    target = scope,
                    previousNumber = previousNumber
                )
            }
            refresh()
        }
    }

    /** Deletes the override for a number, automatically falling back to the next layer. */
    fun deleteFragment(number: Int) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) { catalog.deleteOverride(number, projectRoot) }
            refresh()
        }
    }

    /** Drag reorder: renumbers in the new order and persists, changing the injection order. Updates local state optimistically to avoid list jumping from a re-read. */
    fun reorderFragments(reordered: List<PromptFragment>) {
        val numbers = reordered.map { it.number }.sorted()
        val renumbered = reordered.mapIndexed { index, fragment -> fragment.copy(number = numbers[index]) }
        _state.update { it.copy(fragments = renumbered) }
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val refreshed = withContext(Dispatchers.IO) {
                if (catalog.reorder(renumbered, projectRoot)) catalog.list(projectRoot) else null
            }
            if (refreshed != null) {
                _state.update { it.copy(fragments = refreshed) }
            } else {
                refresh()
            }
        }
    }

    fun setBuiltinDisabled(disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { catalog.setBuiltinDisabled(disabled) }
            refresh()
        }
    }
}
