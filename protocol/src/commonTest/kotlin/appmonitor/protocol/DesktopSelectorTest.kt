package appmonitor.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSelectorTest {
    @Test
    fun answerWithTxtIsChosenAtOnce() {
        val selector = DesktopSelector()

        assertEquals(answer("AppMonitor", path = "/v2/ws"), selector.offer(answer("AppMonitor", path = "/v2/ws")))
    }

    @Test
    fun lateTxtCompletesTheKeptDesktop() {
        val selector = DesktopSelector()

        assertNull(selector.offer(answer("AppMonitor", path = null)))
        assertTrue(selector.hasCandidate)
        assertEquals("/v2/ws", selector.offer(answer("AppMonitor", path = "/v2/ws"))?.path)
    }

    @Test
    fun noTxtSettlesOnTheDefaultPath() {
        val selector = DesktopSelector()

        selector.offer(answer("AppMonitor", path = null))

        assertEquals(Protocol.WEBSOCKET_PATH, selector.settle()?.path)
    }

    @Test
    fun aLaterAnswerWithoutTxtKeepsThePathAlreadySeen() {
        val selector = DesktopSelector()

        selector.offer(answer("AppMonitor", host = "10.0.0.1", path = "/v2/ws"))
        val updated = selector.offer(answer("AppMonitor", host = "10.0.0.2", path = null))

        assertEquals(answer("AppMonitor", host = "10.0.0.2", path = "/v2/ws"), updated)
    }

    @Test
    fun theFirstDesktopIsKeptAndOtherNamesAreIgnored() {
        val selector = DesktopSelector()

        selector.offer(answer("First", host = "10.0.0.1", path = null))

        assertNull(selector.offer(answer("Second", host = "10.0.0.2", path = "/v1/ws")))
        assertEquals("10.0.0.1", selector.offer(answer("FIRST", host = "10.0.0.1", path = "/v1/ws"))?.host)
    }

    @Test
    fun serviceNameMatchesCaseInsensitively() {
        val selector = DesktopSelector(serviceName = "appmonitor")

        assertNull(selector.offer(answer("Other", path = "/v1/ws")))
        assertFalse(selector.hasCandidate)
        assertEquals("AppMonitor", selector.offer(answer("AppMonitor", path = "/v1/ws"))?.name)
    }

    @Test
    fun nothingKeptSettlesOnNull() {
        assertNull(DesktopSelector().settle())
    }

    private fun answer(
        name: String,
        host: String = "10.0.0.1",
        path: String?,
    ) = DesktopAnswer(name, host, Protocol.DEFAULT_PORT, path)
}
