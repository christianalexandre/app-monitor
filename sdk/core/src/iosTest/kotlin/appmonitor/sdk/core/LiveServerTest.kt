package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.getenv
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Reaches a running desktop (`make server`) from the simulator; skipped without `APPMONITOR_LIVE_HOST` (ADR-0067). */
class LiveServerTest {
    @OptIn(ExperimentalForeignApi::class)
    private val host = getenv("APPMONITOR_LIVE_HOST")?.toKString()

    @Test
    fun handshakesWithARunningDesktop() {
        val host = host ?: return println("LiveServerTest skipped: APPMONITOR_LIVE_HOST is not set")
        runBlocking {
            val hello =
                Hello(
                    protocol_version = Protocol.VERSION,
                    platform = Platform.PLATFORM_IOS,
                    device_id = "kmp-live-test",
                    device_name = "iOS simulator",
                    app_id = "appmonitor.sdk.core.test",
                    sdk_version = SDK_VERSION,
                    channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK),
                )
            val client = MonitorClient({ hello }, { Endpoint(host) }, log = ::platformLog)
            client.start()
            try {
                withTimeout(20.seconds) { client.state.first { it == ClientState.Connected } }
            } finally {
                client.stopAndJoin()
            }
        }
    }
}
