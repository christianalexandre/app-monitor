package appmonitor.desktop.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import appmonitor.desktop.app.di.appModule
import appmonitor.desktop.app.store.HeldCalls
import appmonitor.desktop.app.store.Replays
import appmonitor.desktop.data.runRepository
import appmonitor.desktop.data.trafficRepository
import appmonitor.desktop.designsystem.AppMonitorTheme
import appmonitor.desktop.designsystem.AppSurface
import appmonitor.desktop.domain.AnsweredDeviceRepository
import appmonitor.desktop.domain.AppearanceRepository
import appmonitor.desktop.domain.ApprovalRepository
import appmonitor.desktop.domain.DeviceConnectionRepository
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.McpRepository
import appmonitor.desktop.domain.PaneLayoutRepository
import appmonitor.desktop.domain.ProxyRepository
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.ThemeMode
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.feature.breakpoints.BreakpointList
import appmonitor.desktop.feature.breakpoints.BreakpointsViewModel
import appmonitor.desktop.feature.breakpoints.HeldCallDialog
import appmonitor.desktop.feature.breakpoints.breakpointsLabel
import appmonitor.desktop.feature.insights.Benchmarks
import appmonitor.desktop.feature.insights.InsightsViewModel
import appmonitor.desktop.feature.insights.IssueList
import appmonitor.desktop.feature.insights.RunDiff
import appmonitor.desktop.feature.insights.SaveIssueDialog
import appmonitor.desktop.feature.insights.harFileName
import appmonitor.desktop.feature.insights.saveHarDialog
import appmonitor.desktop.feature.mocks.MockDraft
import appmonitor.desktop.feature.mocks.MockList
import appmonitor.desktop.feature.mocks.MocksViewModel
import appmonitor.desktop.feature.mocks.mockDraftOf
import appmonitor.desktop.feature.mocks.mocksLabel
import appmonitor.desktop.feature.settings.Settings
import appmonitor.desktop.feature.settings.SettingsViewModel
import appmonitor.desktop.feature.settings.retentionWarning
import appmonitor.desktop.feature.traffic.PendingDecisions
import appmonitor.desktop.feature.traffic.TrafficLinks
import appmonitor.desktop.feature.traffic.TrafficScreen
import appmonitor.desktop.feature.traffic.TrafficViewModel
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.Storage
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.koinApplication
import java.io.File

/**
 * The traffic panes under the device to approve ([approvals] when given) with Disconnect ([connections] when given), the runs or the
 * settings ([answered], [mcp], [proxy] and [appearance] when given; light without [appearance]); the panes as [panes] left them, the defaults without it. Every pane reads [traffic], [runRepository] or [storage] and follows their commits (SDD 09).
 */
@Composable
internal fun AppMonitorApp(
    storage: Storage,
    status: String,
    approvals: ApprovalRepository? = null,
    answered: AnsweredDeviceRepository? = null,
    connections: DeviceConnectionRepository? = null,
    onClearAll: () -> Unit = {},
    mcp: McpRepository? = null,
    proxy: ProxyRepository? = null,
    pickHarFile: (suggested: String) -> File? = ::saveHarDialog,
    heldCalls: HeldCalls? = null,
    replays: Replays? = null,
    appearance: AppearanceRepository? = null,
    panes: PaneLayoutRepository? = null,
    live: LiveTraffic = remember { LiveTraffic() },
    runRepository: RunRepository = remember(storage) { storage.runRepository() },
    traffic: TrafficRepository = remember(storage, live) { storage.trafficRepository(live) },
) {
    val koin =
        remember(
            storage,
            live,
            runRepository,
            traffic,
            replays,
            heldCalls,
            approvals,
            answered,
            connections,
            mcp,
            proxy,
            appearance,
            panes,
        ) {
            koinApplication {
                modules(
                    appModule(
                        storage,
                        live,
                        runRepository,
                        traffic,
                        replays,
                        heldCalls,
                        approvals,
                        answered,
                        connections,
                        mcp,
                        proxy,
                        appearance,
                        panes,
                    ),
                )
            }
        }
    DisposableEffect(koin) { onDispose { koin.close() } }
    // ADR-0136: the window's view models live as long as this composition.
    val owner = remember(koin) { WindowViewModels() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        KoinIsolatedContext(koin) {
            AppMonitorWindow(status, onClearAll, pickHarFile, traffic, appearance)
        }
    }
}

private class WindowViewModels : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@Composable
private fun AppMonitorWindow(
    status: String,
    onClearAll: () -> Unit,
    pickHarFile: (suggested: String) -> File?,
    traffic: TrafficRepository,
    appearance: AppearanceRepository?,
) {
    val theme = appearance?.theme?.collectAsState()?.value ?: ThemeMode.Light
    val viewModel = koinViewModel<TrafficViewModel>()
    val state by viewModel.state.collectAsState()
    val shownRunId = state.runs.shownRunId
    val filter = state.filter
    val retentionState =
        koinViewModel<SettingsViewModel>()
            .state
            .collectAsState()
            .value.retentionState
    var settingsOpen by remember { mutableStateOf(false) }
    var issuesOpen by remember { mutableStateOf(false) }
    // Null: closed; a draft opens the editor on it.
    var mocksOpen by remember { mutableStateOf<MockDraft?>(null) }
    var mocksShown by remember { mutableStateOf(false) }
    val mocks by koinViewModel<MocksViewModel>().state.collectAsState()
    var breakpointsOpen by remember { mutableStateOf(false) }
    val breakpoints by koinViewModel<BreakpointsViewModel>().state.collectAsState()
    val held = breakpoints.held
    val everySession by remember(traffic) { traffic.observeSessions(null) }.collectAsState(emptyList())
    val appIds = remember(everySession) { everySession.map { it.hello.app_id }.distinct().sorted() }
    var benchmarksOpen by remember { mutableStateOf(false) }
    var diffOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Map<Long, Exchange>?>(null) }
    val insights = koinViewModel<InsightsViewModel>()
    val exportNote =
        insights.state
            .collectAsState()
            .value.exportNote
    val runTitle = state.runs.title

    AppMonitorTheme(dark = if (theme == ThemeMode.System) isSystemInDarkTheme() else theme == ThemeMode.Dark) {
        AppSurface {
            TrafficScreen(
                TrafficLinks(
                    status = status,
                    retentionWarning = retentionWarning(retentionState),
                    mocksLabel = mocksLabel(mocks.pending),
                    breakpointsLabel = breakpointsLabel(held.size),
                    exportNote = exportNote,
                    onSettings = { settingsOpen = true },
                    onIssues = { issuesOpen = true },
                    onMocks = {
                        mocksOpen = null
                        mocksShown = true
                    },
                    onBreakpoints = { breakpointsOpen = true },
                    onBenchmarks = { benchmarksOpen = true },
                    onExportHar = {
                        pickHarFile(harFileName(shownRunId))?.let { file -> insights.exportHar(filter.copy(runId = shownRunId), file) }
                    },
                    onSaveAsIssue = { saving = viewModel.toSave() },
                    onCompareRuns = { diffOpen = true },
                    onMock = { exchange ->
                        mocksOpen =
                            mockDraftOf(
                                exchange,
                                state.detail.appId,
                            )
                        mocksShown = true
                    },
                ),
                viewModel,
            )
            if (settingsOpen) {
                Settings(
                    onClearAll = {
                        viewModel.reset()
                        onClearAll()
                    },
                ) { settingsOpen = false }
            }
            saving?.let { exchanges ->
                SaveIssueDialog(
                    exchanges.keys,
                    defaultTitle = exchanges.values.minBy { it.id }.let { "${it.method ?: "—"} ${it.url ?: it.requestId}" },
                    onSaved = {
                        viewModel.clearMarked()
                        saving = null
                        issuesOpen = true
                    },
                    onClose = { saving = null },
                )
            }
            if (issuesOpen) IssueList(onClose = { issuesOpen = false })
            if (mocksShown) {
                MockList(appIds, mocksOpen, onClose = {
                    mocksShown = false
                    mocksOpen = null
                })
            }
            if (breakpointsOpen) BreakpointList(appIds, onClose = { breakpointsOpen = false })
            if (benchmarksOpen) {
                val devices = if (shownRunId == null) "Every device in the live list" else "Every device of $runTitle"
                val scope = devices + if (filter == ExchangeFilter()) "" else ", under the request filter"
                Benchmarks(filter.copy(runId = shownRunId), scope, onClose = { benchmarksOpen = false })
            }
            if (diffOpen) {
                val scope =
                    if (filter ==
                        ExchangeFilter()
                    ) {
                        "Every device of each run"
                    } else {
                        "Every device of each run, under the request filter"
                    }
                RunDiff(state.runs.runs, filter, scope, onClose = { diffOpen = false })
            }
            // A newer hit under the same request and stage is a new form, not the old one's edits.
            held.firstOrNull()?.let { call -> key(call) { HeldCallDialog(call, held.size - 1) } }
            PendingDecisions()
        }
    }
}
