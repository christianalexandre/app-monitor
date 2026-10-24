package appmonitor.sdk.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.getenv
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reaches a running desktop (`make server`) from the simulator (ADR-0067): by `APPMONITOR_LIVE_HOST`, or
 * by Bonjour when `APPMONITOR_LIVE_DISCOVERY=1`; each skipped without its variable.
 */
@OptIn(ExperimentalForeignApi::class)
class LiveServerTest {
    private val host = getenv("APPMONITOR_LIVE_HOST")?.toKString()
    private val bonjour = getenv("APPMONITOR_LIVE_DISCOVERY")?.toKString() == "1"

    @Test
    fun handshakesWithARunningDesktop() {
        val host = host ?: return println("LiveServerTest skipped: APPMONITOR_LIVE_HOST is not set")
        connects { Endpoint(host) }
    }

    @Test
    fun findsARunningDesktopByBonjour() {
        if (!bonjour) return println("LiveServerTest skipped: APPMONITOR_LIVE_DISCOVERY is not 1")
        connects(discovery(AppMonitorOptions(), bonjourBrowser(), fallbackHost = null))
    }

    private fun connects(discover: suspend () -> Endpoint?) =
        runBlocking {
            val hello = iosHello(Path(SystemTemporaryDirectory, "appmonitor-live-device-id"))
            val client = MonitorClient({ hello }, { discover().also { println("LiveServerTest endpoint: $it") } }, log = ::platformLog)
            client.start()
            try {
                withTimeout(20.seconds) { client.state.first { it == ClientState.Connected } }
            } finally {
                client.stopAndJoin()
            }
        }
}
