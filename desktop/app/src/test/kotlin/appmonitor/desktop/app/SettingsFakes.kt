package appmonitor.desktop.app

import appmonitor.desktop.domain.AnsweredDevice
import appmonitor.desktop.domain.AnsweredDeviceRepository
import appmonitor.desktop.domain.McpRepository
import appmonitor.desktop.domain.ProxyRepository
import appmonitor.desktop.domain.ServiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stored answers that stay as they are; [forgotten] records each forget, null for "Forget all". */
internal class FakeAnswered(
    list: List<AnsweredDevice>,
) : AnsweredDeviceRepository {
    val forgotten = mutableListOf<Pair<String, String>?>()
    override val answered = MutableStateFlow(list)

    override suspend fun forget(
        deviceId: String,
        appId: String,
    ) {
        forgotten += deviceId to appId
    }

    override suspend fun forgetAll() {
        forgotten += null
    }
}

/** An MCP server that stays in [status]; [switched] records each switch. */
internal class FakeMcp(
    status: ServiceStatus,
    override val port: Int,
    token: String,
) : McpRepository {
    val switched = mutableListOf<Boolean>()
    override val status: StateFlow<ServiceStatus> = MutableStateFlow(status)
    override val enabled = MutableStateFlow(true)
    override val token: StateFlow<String> = MutableStateFlow(token)
    override val path = "/mcp"

    override suspend fun setEnabled(on: Boolean) {
        switched += on
    }

    override suspend fun regenerate() = Unit
}

/** A proxy that stays in [status]; [switched] and [resets] record the settings' calls. */
internal class FakeProxy(
    status: ServiceStatus,
    override val port: Int,
    override val caFile: String,
) : ProxyRepository {
    val switched = mutableListOf<Boolean>()
    var resets = 0
    override val status: StateFlow<ServiceStatus> = MutableStateFlow(status)
    override val enabled = MutableStateFlow(true)
    override val host = "127.0.0.1"

    override suspend fun setEnabled(on: Boolean) {
        switched += on
    }

    override suspend fun resetCa() {
        resets++
    }
}
