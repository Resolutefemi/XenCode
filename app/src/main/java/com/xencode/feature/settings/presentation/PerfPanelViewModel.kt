package com.xencode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.xencode.core.perf.PerfFilesInfo
import com.xencode.core.perf.PerfMonitor
import com.xencode.core.perf.PerfSample
import com.xencode.core.perf.PerfSystem
import com.xencode.core.perf.PerfTotals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

/**
 * Performance panel: directly forwards [PerfMonitor]'s state flows, and delegates all operations too.
 * Aggregation (module averages/peaks) is deliberately computed live in the UI layer — the sample flow changes only once per second, so the cost is negligible,
 * and the payoff is that changing aggregation semantics requires no ViewModel changes.
 */
@HiltViewModel
class PerfPanelViewModel @Inject constructor(
    private val perf: PerfMonitor
) : ViewModel() {

    val enabled: StateFlow<Boolean> = perf.enabled
    val module: StateFlow<String> = perf.module
    val samples: StateFlow<List<PerfSample>> = perf.samples
    val totals: StateFlow<PerfTotals> = perf.totals
    val system: StateFlow<PerfSystem> = perf.system
    val filesInfo: StateFlow<PerfFilesInfo> = perf.filesInfo

    fun setEnabled(on: Boolean) = perf.setEnabled(on)

    suspend fun exportRaw(): File = perf.exportRawFile()

    suspend fun exportCsv(): File = perf.exportSummaryCsv()

    fun clearHistory() = perf.clearHistory()
}
