package appmonitor.desktop.feature.insights

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs a `BenchmarkRepository`, a `HarRepository` and an `IssueRepository`. */
val insightsModule =
    module {
        viewModel { InsightsViewModel(get(), get()) }
        viewModel { IssuesViewModel(get()) }
    }
