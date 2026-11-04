package com.aicode.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.data.EditorSettingsRepository
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Editor page state. */
sealed interface EditorUiState {
    data object Loading : EditorUiState

    /**
     * [contentHash] (computed once at load time) gives recomposition keys a cheap document
     * identity: comparing a full [content] string for equality on every frame stalls large files.
     *
     * [path] states which document [content] belongs to. The screen must not render a Success
     * whose [path] differs from the active tab: until the new file's read completes, the state
     * still describes the previous document, and binding the new path to the old text would both
     * flash wrong content and let the dispose-time snapshot write it into the new tab's draft.
     */
    data class Success(
        val path: String,
        val content: String,
        val scopeName: String?,
        val contentHash: Int = content.hashCode()
    ) : EditorUiState

    data class TooLarge(val sizeBytes: Long) : EditorUiState
    data object Binary : EditorUiState
    data class Error(val detail: String?) : EditorUiState
}

/** One-shot save result, delivered through [CodeEditorViewModel.saveEvents]. */
sealed interface SaveResult {
    data object Success : SaveResult
    data class Error(val detail: String?) : SaveResult
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val editorSettings: EditorSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _saveEvents = Channel<SaveResult>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    /** True while writing to disk (SFTP in remote mode can take a while; UI shows a spinner). */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _dirtyPaths = MutableStateFlow<Set<String>>(emptySet())
    val dirtyPaths: StateFlow<Set<String>> = _dirtyPaths.asStateFlow()

    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

    /**
     * Renders the given editor text (the unsaved draft included) as an Android layout
     * snapshot. Resource lookup still goes through the on-disk res/ tree the file lives in;
     * only the layout XML body comes from the editor. Returns the raster plus render notes
     * (FAILED outcome carries the reason in [com.aicode.core.xmlrender.RenderOutcome.errorMessage]).
     */
    suspend fun renderLayoutPreview(
        layoutXml: String,
        path: String,
        dark: Boolean,
        widthDp: Int = com.aicode.core.xmlrender.XmlRenderEngine.DEFAULT_WIDTH_DP,
        heightDp: Int = com.aicode.core.xmlrender.XmlRenderEngine.DEFAULT_HEIGHT_DP
    ): com.aicode.core.xmlrender.RenderOutcome =
        withContext(Dispatchers.Default) {
            com.aicode.core.xmlrender.XmlRenderEngine.render(
                context = context,
                fileAccess = fileAccess,
                layoutPath = path,
                widthDp = widthDp,
                heightDp = heightDp,
                dark = dark,
                xmlOverride = layoutXml
            )
        }

    private var loadedPath: String? = null
    private val buffers = LinkedHashMap<String, FileBuffer>()

    /** Safe to call on recomposition: the same path is not re-read from disk. */
    fun load(path: String) {
        if (path.isBlank()) return
        if (loadedPath == path && _uiState.value !is EditorUiState.Loading) return
        loadedPath = path
        val cached = buffers[path]
        if (cached != null) {
            val tooLarge = cached.tooLargeBytes
            _uiState.value = when {
                tooLarge != null -> EditorUiState.TooLarge(tooLarge)
                cached.binary -> EditorUiState.Binary
                cached.error != null -> EditorUiState.Error(cached.error)
                else -> EditorUiState.Success(
                    path = path,
                    content = cached.draft ?: cached.diskContent.orEmpty(),
                    scopeName = cached.scopeName
                )
            }
            return
        }
        _uiState.value = EditorUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (!fileAccess.exists(path) || !fileAccess.isFile(path)) {
                    return@runCatching FileBuffer(error = null)
                }
                val size = fileAccess.fileSize(path)
                if (size > MAX_EDITABLE_BYTES) {
                    return@runCatching FileBuffer(tooLargeBytes = size)
                }
                val bytes = fileAccess.readBytes(path)
                if (looksBinary(bytes)) {
                    return@runCatching FileBuffer(binary = true)
                }
                TextMateSetup.ensureInitialized(context)
                val scope = TextMateSetup.scopeNameFor(path)
                if (scope != null) {
                    runCatching { TextMateLanguage.create(scope, false).destroy() }
                }
                FileBuffer(
                    diskContent = bytes.toString(Charsets.UTF_8),
                    scopeName = scope
                )
            }.getOrElse { e ->
                FileLogger.w(TAG, "Failed to open file: $path", e)
                FileBuffer(error = e.message)
            }
            buffers[path] = result
            if (loadedPath != path) return@launch
            _uiState.value = result.toUiState(path)
        }
    }

    /**
     * Store the live editor text as this document's draft. Called at capture points (tab switch,
     * screen exit, activity recreation) and derives dirty from the disk content, so the save flow
     * never depends on the composable's local dirty flag.
     */
    fun captureDraft(path: String, content: String) {
        val buf = buffers[path] ?: return
        val dirty = content != buf.diskContent
        buf.draft = if (dirty) content else null
        _dirtyPaths.value = if (dirty) _dirtyPaths.value + path else _dirtyPaths.value - path
    }

    /**
     * Track dirty state without copying text. Called on every content change while typing, so it
     * must stay O(1); the full text is captured later by [captureDraft] at switch/exit points.
     */
    fun markDirty(path: String, dirty: Boolean) {
        if (path.isBlank()) return
        val buffer = buffers[path]
        // A stale draft would keep serving old content after leaving the document; when the file
        // reports clean the live editor matches the baseline, so drop the draft.
        if (!dirty) buffer?.draft = null
        _dirtyPaths.value = if (dirty) _dirtyPaths.value + path else _dirtyPaths.value - path
    }

    /** Current editable text for [path]: the unsaved draft if any, else the disk content. */
    fun draftOf(path: String): String? {
        val buffer = buffers[path] ?: return null
        return buffer.draft ?: buffer.diskContent
    }

    /** Write the editor contents back to disk. Runs on IO; result via [saveEvents]; [saving] while in flight. */
    fun save(content: String, path: String? = loadedPath) {
        val target = path ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                val result = runCatching { fileAccess.writeFile(target, content) }
                    .fold(
                        onSuccess = { SaveResult.Success },
                        onFailure = { e ->
                            FileLogger.w(TAG, "Failed to save file: $target", e)
                            SaveResult.Error(e.message)
                        }
                    )
                if (result is SaveResult.Success) {
                    val buf = buffers[target]
                    if (buf != null) {
                        buf.diskContent = content
                        buf.draft = null
                    }
                    _dirtyPaths.value = _dirtyPaths.value - target
                }
                _saveEvents.send(result)
            } finally {
                _saving.value = false
            }
        }
    }

    private fun FileBuffer.toUiState(path: String): EditorUiState {
        val tooLarge = tooLargeBytes
        return when {
            tooLarge != null -> EditorUiState.TooLarge(tooLarge)
            binary -> EditorUiState.Binary
            error != null || diskContent == null && draft == null -> EditorUiState.Error(error)
            else -> EditorUiState.Success(
                path = path,
                content = draft ?: diskContent.orEmpty(),
                scopeName = scopeName
            )
        }
    }

    private data class FileBuffer(
        var diskContent: String? = null,
        var draft: String? = null,
        var scopeName: String? = null,
        var tooLargeBytes: Long? = null,
        var binary: Boolean = false,
        var error: String? = null
    )

    private companion object {
        const val TAG = "CodeEditorViewModel"

        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024
        const val BINARY_SNIFF_BYTES = 8000

        fun looksBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, BINARY_SNIFF_BYTES)
            for (i in 0 until limit) {
                if (bytes[i].toInt() == 0) return true
            }
            return false
        }
    }
}
