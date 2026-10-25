package appmonitor.desktop.feature.traffic

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Needs a `RunRepository`, a `TrafficRepository` and an `ExportRepository`; a `ReplayRepository`, an
 * `AskedReplayRepository` and an `ApprovalRepository` are optional.
 */
val trafficModule =
    module {
        viewModel { TrafficViewModel(get(), get(), get(), getOrNull()) }
        viewModel { DecisionsViewModel(getOrNull(), getOrNull()) }
    }
