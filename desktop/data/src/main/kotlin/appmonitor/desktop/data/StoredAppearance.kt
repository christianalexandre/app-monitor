package appmonitor.desktop.data

import appmonitor.desktop.domain.AppearanceRepository
import appmonitor.desktop.domain.SettingRepository
import appmonitor.desktop.domain.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The stored theme, [ThemeMode.System] when unset or unreadable (SDD 09). */
suspend fun SettingRepository.appearanceRepository(): AppearanceRepository =
    StoredAppearanceRepository(this, ThemeMode.entries.firstOrNull { it.name.lowercase() == setting(KEY_THEME) } ?: ThemeMode.System)

/** The settings key [appearanceRepository] reads and writes. */
const val KEY_THEME = "ui.theme"

internal class StoredAppearanceRepository(
    private val settings: SettingRepository,
    theme: ThemeMode,
) : AppearanceRepository {
    private val mutableTheme = MutableStateFlow(theme)

    override val theme: StateFlow<ThemeMode> = mutableTheme.asStateFlow()

    override suspend fun setTheme(theme: ThemeMode) {
        mutableTheme.value = theme
        settings.setSetting(KEY_THEME, theme.name.lowercase())
    }
}
