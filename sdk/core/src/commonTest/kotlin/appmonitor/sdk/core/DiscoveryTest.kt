package appmonitor.sdk.core

import appmonitor.protocol.DesktopAnswer
import appmonitor.protocol.Protocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryTest {
    private val config = DiscoveryConfig(mdnsBeforeFallback = 3.seconds, txtGrace = 500.milliseconds)

    @Test
    fun manualHostIsTheOnlySource() =
        runTest {
            val browser = FakeBrowser()
            val discover =
                discovery(AppMonitorOptions(host = "192.168.1.20", port = 4000), { browser }, fallbackHost = EMULATOR_HOST, config)

            assertEquals(Endpoint("192.168.1.20", 4000, checksPin = false), discover())
            assertEquals(0, browser.browses)
        }

    @Test
    fun aPinnedDeviceSkipsOtherDesktops() =
        runTest {
            val browser =
                FakeBrowser {
                    it(answer("Colleague", "192.168.1.30", "/v1/ws", id = "theirs"))
                    it(answer("Mine", "192.168.1.20", "/v1/ws", id = "mine"))
                }
            val discover = discovery(AppMonitorOptions(), { browser }, fallbackHost = null, config, pinned = { "mine" })

            assertEquals(Endpoint("192.168.1.20", Protocol.DEFAULT_PORT, "/v1/ws"), discover())
        }

    @Test
    fun mdnsComesBeforeTheEmulatorHost() =
        runTest {
            val browser = FakeBrowser { it(answer("AppMonitor", "192.168.1.20", "/v1/ws")) }
            val discover = discovery(AppMonitorOptions(), { browser }, fallbackHost = EMULATOR_HOST, config)

            assertEquals(Endpoint("192.168.1.20", Protocol.DEFAULT_PORT, "/v1/ws"), discover())
            assertEquals(1, browser.closes)
        }

    @Test
    fun emulatorHostFollowsAnMdnsThatFindsNothing() =
        runTest {
            val browser = FakeBrowser()
            val discover = discovery(AppMonitorOptions(), { browser }, fallbackHost = EMULATOR_HOST, config)

            assertEquals(Endpoint(EMULATOR_HOST), discover())
            assertEquals(3_000, testScheduler.currentTime)
            assertEquals(1, browser.closes)
        }

    @Test
    fun emulatorHostFollowsAnMdnsThatMayNotRun() =
        runTest {
            val discover = discovery(AppMonitorOptions(), { null }, fallbackHost = EMULATOR_HOST, config)

            assertEquals(Endpoint(EMULATOR_HOST), discover())
            assertEquals(0, testScheduler.currentTime)
        }

    @Test
    fun emulatorHostFollowsAnMdnsThatFails() =
        runTest {
            val logs = mutableListOf<String>()
            val failing = MdnsBrowser { error("NsdManager unavailable") }
            val discover = discovery(AppMonitorOptions(), { failing }, fallbackHost = EMULATOR_HOST, config, log = { logs += it })

            assertEquals(Endpoint(EMULATOR_HOST), discover())
            assertTrue(logs.single().contains("NsdManager unavailable"), logs.toString())
        }

    @Test
    fun aDeviceWaitsForMdnsUntilTheClientTimesOut() =
        runTest {
            val browser = FakeBrowser()
            val discover = discovery(AppMonitorOptions(), { browser }, fallbackHost = null, config)

            assertNull(withTimeoutOrNull(5.seconds) { discover() })
            assertEquals(1, browser.closes)
        }

    @Test
    fun aDeviceWithoutMdnsFindsNothing() =
        runTest {
            assertNull(discovery(AppMonitorOptions(), { null }, fallbackHost = null, config)())
        }

    @Test
    fun aLateTxtRecordGivesThePath() =
        runTest {
            val browser = FakeBrowser()
            val discover = discovery(AppMonitorOptions(), { browser }, fallbackHost = null, config)
            launch {
                browser.answer(answer("AppMonitor", "192.168.1.20", path = null))
                delay(400)
                browser.answer(answer("AppMonitor", "192.168.1.20", path = "/v2/ws"))
            }

            assertEquals(Endpoint("192.168.1.20", Protocol.DEFAULT_PORT, "/v2/ws"), discover())
        }

    @Test
    fun noTxtRecordWithinTheGraceUsesTheDefaultPath() =
        runTest {
            val browser = FakeBrowser()
            val discover = discovery(AppMonitorOptions(), { browser }, fallbackHost = null, config)
            launch {
                browser.answer(answer("AppMonitor", "192.168.1.20", path = null))
                delay(600)
                browser.answer(answer("AppMonitor", "192.168.1.20", path = "/v2/ws"))
            }

            assertEquals(Endpoint("192.168.1.20", Protocol.DEFAULT_PORT, Protocol.WEBSOCKET_PATH), discover())
            assertEquals(500, testScheduler.currentTime)
        }

    @Test
    fun theFirstDesktopAnsweredIsKeptOverAnotherName() =
        runTest {
            val browser =
                FakeBrowser {
                    it(answer("Desk A", "192.168.1.20", path = null))
                    it(answer("Desk B", "192.168.1.30", path = "/v1/ws"))
                }
            val discover = discovery(AppMonitorOptions(), { browser }, fallbackHost = null, config)

            assertEquals(Endpoint("192.168.1.20"), discover())
        }

    private fun answer(
        name: String,
        host: String,
        path: String?,
        id: String? = null,
    ) = DesktopAnswer(name, host, Protocol.DEFAULT_PORT, path, id)

    /** Answers with [onBrowse] when browsing starts, or later through [answer]. */
    private class FakeBrowser(
        private val onBrowse: ((DesktopAnswer) -> Unit) -> Unit = {},
    ) : MdnsBrowser {
        var browses = 0
        var closes = 0
        private var sink: ((DesktopAnswer) -> Unit)? = null

        fun answer(answer: DesktopAnswer) = checkNotNull(sink)(answer)

        override fun browse(onAnswer: (DesktopAnswer) -> Unit): AutoCloseable {
            browses++
            sink = onAnswer
            onBrowse(onAnswer)
            return AutoCloseable { closes++ }
        }
    }
}
