package appmonitor.desktop.feature.breakpoints

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs a `BreakpointRepository`; a `HeldCallRepository` is optional. */
val breakpointsModule =
    module {
        viewModel { BreakpointsViewModel(get(), getOrNull()) }
    }
