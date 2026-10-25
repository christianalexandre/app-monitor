package appmonitor.desktop.feature.insights

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs a `BenchmarkRepository` and a `HarRepository`. */
val insightsModule =
    module {
        viewModel { InsightsViewModel(get(), get()) }
    }
