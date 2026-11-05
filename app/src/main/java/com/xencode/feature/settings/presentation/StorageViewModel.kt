package com.xencode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.feature.settings.domain.model.CleanupKind
import com.xencode.feature.settings.domain.model.DeviceSpace
import com.xencode.feature.settings.domain.model.StorageCategory
import com.xencode.feature.settings.domain.model.StorageDetailKey
import com.xencode.feature.settings.domain.model.StorageEntry
import com.xencode.feature.settings.domain.service.StorageUsageScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    /** Computed categories; filled in while scanning, missing items show "Calculating" in the UI. */
    val entries: Map<StorageCategory, StorageEntry> = emptyMap(),
    val scanning: Boolean = false,
    val deviceSpace: DeviceSpace = DeviceSpace(0, 0),
    val expanded: Set<StorageCategory> = emptySet(),
    val cleaning: Boolean = false,
    val selectedCleanup: Set<CleanupKind> = setOf(CleanupKind.Caches, CleanupKind.Logs),
    /** Bytes freed by the last cleanup; the UI shows a hint when non-null. */
    val freedBytes: Long? = null
) {
    val totalBytes: Long get() = entries.values.sumOf { it.bytes }

    /** Current size of each cleanable item; items not yet counted are treated as 0, so the UI disables buttons accordingly. */
    val cleanableSizes: Map<CleanupKind, Long>
        get() = mapOf(
            CleanupKind.Caches to (entries[StorageCategory.Caches]?.bytes ?: 0L),
            CleanupKind.Logs to (entries[StorageCategory.Logs]?.bytes ?: 0L),
            CleanupKind.ToolOutput to aiConfigDetail(StorageDetailKey.TOOL_OUTPUT),
            CleanupKind.VisionSessions to aiConfigDetail(StorageDetailKey.VISION_SESSIONS),
            CleanupKind.NetworkHistory to aiConfigDetail(StorageDetailKey.NETWORK_HISTORY)
        )

    val selectedBytes: Long
        get() = cleanableSizes.filterKeys { it in selectedCleanup }.values.sum()

    private fun aiConfigDetail(key: String): Long =
        entries[StorageCategory.AiConfig]?.details?.firstOrNull { it.key == key }?.bytes ?: 0L
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val scanner: StorageUsageScanner
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    /** Hold the scan coroutine separately: the rescan triggered after cleanup must not cancel the cleanup itself. */
    private var scanJob: Job? = null

    init {
        refresh()
    }

    fun refresh(clearMessage: Boolean = true) {
        scanJob?.cancel()
        _state.update {
            it.copy(
                entries = emptyMap(),
                scanning = true,
                deviceSpace = scanner.deviceSpace(),
                freedBytes = if (clearMessage) null else it.freedBytes
            )
        }
        scanJob = viewModelScope.launch {
            scanner.scan().collect { entry ->
                _state.update { s -> s.copy(entries = s.entries + (entry.category to entry)) }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    fun toggleExpanded(category: StorageCategory) {
        _state.update {
            val expanded = if (category in it.expanded) it.expanded - category else it.expanded + category
            it.copy(expanded = expanded)
        }
    }

    fun toggleCleanup(kind: CleanupKind) {
        _state.update {
            if (it.cleaning) return@update it
            val selected = if (kind in it.selectedCleanup) it.selectedCleanup - kind else it.selectedCleanup + kind
            it.copy(selectedCleanup = selected)
        }
    }

    fun clean(kinds: List<CleanupKind>) {
        if (_state.value.cleaning || kinds.isEmpty()) return
        val targets = kinds.distinct()
        viewModelScope.launch {
            _state.update { it.copy(cleaning = true) }
            val freed = targets.sumOf { scanner.clean(it) }
            _state.update { it.copy(cleaning = false, freedBytes = freed) }
            refresh(clearMessage = false)
        }
    }
}
