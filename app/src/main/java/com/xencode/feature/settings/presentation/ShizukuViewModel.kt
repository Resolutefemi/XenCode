package com.xencode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.xencode.feature.agent.domain.shizuku.ShizukuManager
import com.xencode.feature.agent.domain.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** State and action entry points for the Shizuku execution backend settings page. */
@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager
) : ViewModel() {

    val state: StateFlow<ShizukuState> = shizukuManager.state

    /** Re-probe the state (e.g. after returning from the Shizuku app). */
    fun refresh() = shizukuManager.refreshState()

    fun requestPermission() = shizukuManager.requestPermission()

    fun openShizukuApp() = shizukuManager.openShizukuApp()
}
