package appmonitor.desktop.data

import appmonitor.desktop.domain.PaneLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredPaneLayoutTest {
    @Test
    fun `the panes open at their defaults until moved, then as they were left`() =
        withStorage { storage ->
            val settings = storage.settingRepository()
            val panes = settings.paneLayoutRepository()
            assertEquals(PaneLayout(), panes.layout.value)

            val moved = PaneLayout(devicesShown = false, devicesWidth = 300, requestsWidth = 520)
            panes.setLayout(moved)
            assertEquals(moved, panes.layout.value)
            storage.flush()
            assertEquals(moved, settings.paneLayoutRepository().layout.value)
        }

    @Test
    fun `an unreadable stored layout opens at the defaults`() =
        withStorage { storage ->
            val settings = storage.settingRepository()
            listOf("wide", "shown,300", "open,300,500", "shown,-1,500", "shown,300,x").forEach { value ->
                settings.setSetting(KEY_PANES, value)
                storage.flush()
                assertEquals(PaneLayout(), settings.paneLayoutRepository().layout.value, value)
            }
        }
}
