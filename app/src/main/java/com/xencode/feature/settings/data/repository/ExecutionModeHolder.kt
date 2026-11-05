package com.xencode.feature.settings.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronous cache of the execution mode: at app startup the first frame from [ExecutionModeRepository] is cached in memory,
 * so the delegating layers (`DelegatingCommandEngine`/`DelegatingFileAccess`/`DelegatingTerminalSessionProvider`)
 * can read it synchronously on each call to decide whether to forward to the local or remote implementation.
 *
 * After a mode switch (settings page) [setMode] updates the cache; the switch takes effect immediately, no restart needed.
 */
@Singleton
class ExecutionModeHolder @Inject constructor() {
    private val _mode = MutableStateFlow(ExecutionMode.LOCAL_PROOT)
    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    fun setMode(mode: ExecutionMode) {
        _mode.value = mode
    }

    fun currentMode(): ExecutionMode = _mode.value
}
