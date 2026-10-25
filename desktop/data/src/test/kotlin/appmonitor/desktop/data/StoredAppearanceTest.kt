package appmonitor.desktop.data

import appmonitor.desktop.domain.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredAppearanceTest {
    @Test
    fun `the theme follows the system until chosen, then is read back from the store`() =
        withStorage { storage ->
            val settings = storage.settingRepository()
            val appearance = settings.appearanceRepository()
            assertEquals(ThemeMode.System, appearance.theme.value)

            appearance.setTheme(ThemeMode.Dark)
            assertEquals(ThemeMode.Dark, appearance.theme.value)
            storage.flush()
            assertEquals(ThemeMode.Dark, settings.appearanceRepository().theme.value)
        }

    @Test
    fun `an unreadable stored theme follows the system`() =
        withStorage { storage ->
            val settings = storage.settingRepository()
            settings.setSetting(KEY_THEME, "sepia")
            storage.flush()
            assertEquals(ThemeMode.System, settings.appearanceRepository().theme.value)
        }
}
