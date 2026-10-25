package appmonitor.desktop.feature.settings

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs a `RetentionRepository`; an `AnsweredDeviceRepository`, an `McpRepository` and a `ProxyRepository` are optional. */
val settingsModule =
    module {
        viewModel { SettingsViewModel(get(), getOrNull(), getOrNull(), getOrNull()) }
    }
