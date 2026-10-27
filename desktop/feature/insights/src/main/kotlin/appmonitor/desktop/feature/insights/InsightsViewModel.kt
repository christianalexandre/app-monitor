package appmonitor.desktop.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appmonitor.desktop.domain.BenchmarkRepository
import appmonitor.desktop.domain.DeviceComparison
import appmonitor.desktop.domain.EndpointDiff
import appmonitor.desktop.domain.EndpointGroups
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HarRepository
import appmonitor.desktop.domain.HarWrite
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** [benchmarks], [byDevice] and [diff] are null while read and once closed; [exportNote] is the last HAR export's line. */
data class InsightsState(
    val benchmarks: EndpointGroups? = null,
    val byDevice: DeviceComparison? = null,
    val diff: EndpointDiff? = null,
    val exportNote: String? = null,
)

/** The benchmarks, the run diff and the HAR export of the window (SDD 01). */
class InsightsViewModel(
    private val benchmarks: BenchmarkRepository,
    private val hars: HarRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InsightsState())
    val state: StateFlow<InsightsState> = mutableState.asStateFlow()

    private var benchmarksRead: Job? = null
    private var byDeviceRead: Job? = null
    private var diffRead: Job? = null
    private var exports = 0

    /** Reads what [filter] matches again; a read still running is dropped. */
    fun readBenchmarks(filter: ExchangeFilter) {
        closeBenchmarks()
        benchmarksRead =
            viewModelScope.launch {
                val groups = benchmarks.endpoints(filter)
                mutableState.update { it.copy(benchmarks = groups) }
            }
    }

    fun closeBenchmarks() {
        benchmarksRead?.cancel()
        mutableState.update { it.copy(benchmarks = null) }
    }

    /** Reads run [runId] per endpoint and device under [filter]; a read still running is dropped. */
    fun readByDevice(
        filter: ExchangeFilter,
        runId: Long,
    ) {
        closeByDevice()
        byDeviceRead =
            viewModelScope.launch {
                val comparison = benchmarks.byDevice(filter, runId)
                mutableState.update { it.copy(byDevice = comparison) }
            }
    }

    fun closeByDevice() {
        byDeviceRead?.cancel()
        mutableState.update { it.copy(byDevice = null) }
    }

    /** Compares [runId] with [baseRunId] under [filter]; a read still running is dropped. */
    fun readDiff(
        filter: ExchangeFilter,
        baseRunId: Long,
        runId: Long,
    ) {
        closeDiff()
        diffRead =
            viewModelScope.launch {
                val diff = benchmarks.diff(filter, baseRunId, runId)
                mutableState.update { it.copy(diff = diff) }
            }
    }

    fun closeDiff() {
        diffRead?.cancel()
        mutableState.update { it.copy(diff = null) }
    }

    /** Writes what [filter] matches to [file]; [InsightsState.exportNote] says how the newest export went. */
    fun exportHar(
        filter: ExchangeFilter,
        file: File,
    ) {
        val export = ++exports
        mutableState.update { it.copy(exportNote = "Exporting…") }
        viewModelScope.launch {
            val note = noteOf(hars.write(filter, file), file)
            if (export == exports) mutableState.update { it.copy(exportNote = note) }
        }
    }
}

private fun noteOf(
    write: HarWrite,
    file: File,
): String =
    when (write) {
        is HarWrite.Written -> {
            val more = if (write.more) " (the first ${write.maxEntries}; more matched)" else ""
            val connections = if (write.connections > 0) "; ${write.connections} connections left out" else ""
            "Exported ${write.entries} exchanges$more to ${file.name}$connections"
        }

        is HarWrite.NotWritten -> {
            "Not exported: ${write.reason}"
        }
    }
