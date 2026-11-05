package appmonitor.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.traffic.DROPS
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.StoredDrops
import appmonitor.protocol.Protocol
import appmonitor.tools.fakeclient.Fixtures
import appmonitor.v1.DeliveryReport
import appmonitor.v1.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals

// H5.3c: a DeliveryReport crosses a real server into the live list and the devices pane (ADR-0166).
@OptIn(ExperimentalTestApi::class)
class DropsUiTest {
    @Test
    fun `a device's drops show in the devices pane, lost unknown said so`() =
        withStorage { storage ->
            val live = LiveTraffic()
            val report =
                Envelope(
                    channel = Protocol.CHANNEL_CORE,
                    type = "DeliveryReport",
                    // A previous session this desktop never saw: what it lost is unknown.
                    payload =
                        DeliveryReport(
                            evicted = 12,
                            oversized = 1,
                            previous_session_id = "gone",
                            previous_session_written = 40,
                        ).encodeByteString(),
                    timestamp_ms = Fixtures.traffic.first().timestamp_ms,
                )
            live.receive(storage, listOf(report) + Fixtures.traffic)

            val session = live.sessions().single()
            assertEquals(StoredDrops(evicted = 12, oversized = 1, lost = null), session.drops)
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening", appearance = TestAppearance, live = live) }

                awaitText("Dropped: 12 buffer full, 1 too large, lost unknown")
                onNodeWithTag(DROPS + session.id, useUnmergedTree = true).assertExists()
                screenshot("drops.png")
            }
        }
}
