package appmonitor.desktop.data

import appmonitor.desktop.domain.PaneLayout
import appmonitor.desktop.domain.PaneLayoutRepository
import appmonitor.desktop.domain.SettingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The stored panes' layout, the defaults when unset or unreadable (SDD 09, ADR-0153). */
suspend fun SettingRepository.paneLayoutRepository(): PaneLayoutRepository =
    StoredPaneLayoutRepository(this, setting(KEY_PANES)?.let(::paneLayoutOf) ?: PaneLayout())

/** The settings key [paneLayoutRepository] reads and writes: `shown|hidden,<devices dp>,<requests dp>`. */
const val KEY_PANES = "ui.panes"

internal fun paneLayoutOf(value: String): PaneLayout? {
    val parts = value.split(',')
    if (parts.size != 3 || parts[0] !in setOf("shown", "hidden")) return null
    val devices = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val requests = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return PaneLayout(devicesShown = parts[0] == "shown", devicesWidth = devices, requestsWidth = requests)
}

internal fun PaneLayout.toSetting(): String = "${if (devicesShown) "shown" else "hidden"},$devicesWidth,$requestsWidth"

internal class StoredPaneLayoutRepository(
    private val settings: SettingRepository,
    layout: PaneLayout,
) : PaneLayoutRepository {
    private val mutableLayout = MutableStateFlow(layout)

    override val layout: StateFlow<PaneLayout> = mutableLayout.asStateFlow()

    override suspend fun setLayout(layout: PaneLayout) {
        mutableLayout.value = layout
        settings.setSetting(KEY_PANES, layout.toSetting())
    }
}
