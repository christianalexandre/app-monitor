package appmonitor.desktop.app.di

import appmonitor.desktop.app.store.Replays
import appmonitor.desktop.app.ui.StorageExports
import appmonitor.desktop.data.breakpointRepository
import appmonitor.desktop.data.mockRepository
import appmonitor.desktop.domain.BreakpointRepository
import appmonitor.desktop.domain.ExportRepository
import appmonitor.desktop.domain.HeldCallRepository
import appmonitor.desktop.domain.MockRepository
import appmonitor.desktop.domain.ReplayRepository
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.desktop.feature.breakpoints.breakpointsModule
import appmonitor.desktop.feature.mocks.mocksModule
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
): Module =
    module {
        includes(trafficModule, mocksModule, breakpointsModule)
        single<RunRepository> { runRepository }
        single<TrafficRepository> { traffic }
        single<ExportRepository> { StorageExports(storage) }
        single<MockRepository> { storage.mockRepository() }
        single<BreakpointRepository> { storage.breakpointRepository() }
        if (replays != null) single<ReplayRepository> { replays }
        if (heldCalls != null) single<HeldCallRepository> { heldCalls }
    }
