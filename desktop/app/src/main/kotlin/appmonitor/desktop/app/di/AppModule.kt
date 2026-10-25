package appmonitor.desktop.app.di

import appmonitor.desktop.app.store.Replays
import appmonitor.desktop.app.ui.StorageExports
import appmonitor.desktop.data.benchmarkRepository
import appmonitor.desktop.data.breakpointRepository
import appmonitor.desktop.data.issueRepository
import appmonitor.desktop.data.mockRepository
import appmonitor.desktop.data.retentionRepository
import appmonitor.desktop.data.trafficQueryRepository
import appmonitor.desktop.domain.AnsweredDeviceRepository
import appmonitor.desktop.domain.ApprovalRepository
import appmonitor.desktop.domain.AskedReplayRepository
import appmonitor.desktop.domain.BenchmarkRepository
import appmonitor.desktop.domain.BreakpointRepository
import appmonitor.desktop.domain.ExportRepository
import appmonitor.desktop.domain.HarRepository
import appmonitor.desktop.domain.HeldCallRepository
import appmonitor.desktop.domain.IssueRepository
import appmonitor.desktop.domain.McpRepository
import appmonitor.desktop.domain.MockRepository
import appmonitor.desktop.domain.ProxyRepository
import appmonitor.desktop.domain.ReplayRepository
import appmonitor.desktop.domain.RetentionRepository
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.feature.breakpoints.breakpointsModule
import appmonitor.desktop.feature.insights.insightsModule
import appmonitor.desktop.feature.mocks.mocksModule
import appmonitor.desktop.feature.settings.settingsModule
import appmonitor.desktop.feature.traffic.trafficModule
import appmonitor.desktop.storage.Storage
import org.koin.core.module.Module
import org.koin.dsl.module

/** The window's graph (ADR-0136): the repositories over one open store, and every feature's view models. */
internal fun appModule(
    storage: Storage,
    runRepository: RunRepository,
    traffic: TrafficRepository,
    replays: Replays?,
    heldCalls: HeldCallRepository?,
    approvals: ApprovalRepository?,
    answered: AnsweredDeviceRepository?,
    mcp: McpRepository?,
    proxy: ProxyRepository?,
): Module =
    module {
        includes(trafficModule, mocksModule, breakpointsModule, insightsModule, settingsModule)
        single<RunRepository> { runRepository }
        single<TrafficRepository> { traffic }
        single { StorageExports(storage.trafficQueryRepository()) }
        single<ExportRepository> { get<StorageExports>() }
        single<HarRepository> { get<StorageExports>() }
        single<BenchmarkRepository> { storage.benchmarkRepository() }
        single<IssueRepository> { storage.issueRepository() }
        single<MockRepository> { storage.mockRepository() }
        single<BreakpointRepository> { storage.breakpointRepository() }
        single<RetentionRepository> { storage.retentionRepository() }
        if (replays != null) {
            single<ReplayRepository> { replays }
            single<AskedReplayRepository> { replays }
        }
        if (approvals != null) single<ApprovalRepository> { approvals }
        if (heldCalls != null) single<HeldCallRepository> { heldCalls }
        if (answered != null) single<AnsweredDeviceRepository> { answered }
        if (mcp != null) single<McpRepository> { mcp }
        if (proxy != null) single<ProxyRepository> { proxy }
    }
