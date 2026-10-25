package appmonitor.desktop.feature.settings

import appmonitor.desktop.domain.AnsweredDevice
import appmonitor.desktop.domain.AnsweredDeviceRepository
import appmonitor.desktop.domain.AppearanceRepository
import appmonitor.desktop.domain.McpRepository
import appmonitor.desktop.domain.Retention
import appmonitor.desktop.domain.RetentionRepository
import appmonitor.desktop.domain.RetentionState
import appmonitor.desktop.domain.ServiceStatus
import appmonitor.desktop.domain.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val retention = FakeRetention()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `retention is read and written through the repository`() =
        runTest {
            val viewModel = SettingsViewModel(retention)
            assertEquals(Retention(7, 1L shl 30), viewModel.state.value.retention)

            viewModel.setRetention(Retention(null, 2L shl 30))
            retention.state.value = RetentionState(usedBytes = 4096, keptOverBound = true)

            assertEquals(Retention(null, 2L shl 30), viewModel.state.value.retention)
            assertEquals(RetentionState(4096, true), viewModel.state.value.retentionState)
            assertNull(viewModel.state.value.mcp, "no MCP server, no section")
            assertNull(viewModel.state.value.proxy)
            assertNull(viewModel.state.value.theme)
        }

    @Test
    fun `the theme is read and chosen through the repository`() =
        runTest {
            val appearance = FakeAppearance()
            val viewModel = SettingsViewModel(retention, appearance = appearance)
            assertEquals(ThemeMode.System, viewModel.state.value.theme)

            viewModel.setTheme(ThemeMode.Dark)

            assertEquals(ThemeMode.Dark, viewModel.state.value.theme)
            assertEquals(listOf(ThemeMode.Dark), appearance.written)
        }

    @Test
    fun `answered devices are listed and forgotten one or all`() =
        runTest {
            val pixel = AnsweredDevice("d1", "app.a", true, 1, "Pixel", "Android 16", "1.0")
            val answered = FakeAnswered(listOf(pixel))
            val viewModel = SettingsViewModel(retention, answered)

            assertEquals(listOf(pixel), viewModel.state.value.answered)
            viewModel.forget(pixel)
            viewModel.forget(null)

            assertEquals(listOf("d1/app.a", "all"), answered.calls)
        }

    @Test
    fun `the MCP section follows the server's status, switch and token`() =
        runTest {
            val mcp = FakeMcp()
            val viewModel = SettingsViewModel(retention, mcp = mcp)
            assertEquals(McpSettings(ServiceStatus.Off, true, 47475, "/mcp", "t1"), viewModel.state.value.mcp)

            viewModel.setMcpEnabled(false)
            mcp.status.value = ServiceStatus.PortTaken(47475)
            viewModel.regenerateToken()

            assertEquals(McpSettings(ServiceStatus.PortTaken(47475), false, 47475, "/mcp", "t2"), viewModel.state.value.mcp)
        }
}

private class FakeAppearance : AppearanceRepository {
    override val theme = MutableStateFlow(ThemeMode.System)
    val written = mutableListOf<ThemeMode>()

    override suspend fun setTheme(theme: ThemeMode) {
        written += theme
        this.theme.value = theme
    }
}

private class FakeRetention : RetentionRepository {
    override val retention = MutableStateFlow(Retention(7, 1L shl 30))
    override val state = MutableStateFlow(RetentionState())

    override suspend fun setRetention(retention: Retention) {
        this.retention.value = retention
    }
}

private class FakeAnswered(
    list: List<AnsweredDevice>,
) : AnsweredDeviceRepository {
    val calls = mutableListOf<String>()
    override val answered = MutableStateFlow(list)

    override suspend fun forget(
        deviceId: String,
        appId: String,
    ) {
        calls += "$deviceId/$appId"
    }

    override suspend fun forgetAll() {
        calls += "all"
    }
}

private class FakeMcp : McpRepository {
    override val status = MutableStateFlow<ServiceStatus>(ServiceStatus.Off)
    override val enabled = MutableStateFlow(true)
    override val token = MutableStateFlow("t1")
    override val port = 47475
    override val path = "/mcp"

    override suspend fun setEnabled(on: Boolean) {
        enabled.value = on
    }

    override suspend fun regenerate() {
        token.value = "t2"
    }
}
