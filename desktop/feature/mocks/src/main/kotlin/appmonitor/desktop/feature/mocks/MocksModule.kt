package appmonitor.desktop.feature.mocks

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs a `MockRepository`. */
val mocksModule =
    module {
        viewModel { MocksViewModel(get()) }
    }
