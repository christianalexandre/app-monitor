package appmonitor.desktop.storage

import appmonitor.tools.fakeclient.Fixtures
import appmonitor.v1.CaptureLayer
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// M8.4: a `CONNECTION` exchange from the fixtures through every storage reader (ADR-0103, ADR-0104).
class ConnectionTest {
    @Test
    fun aConnectionHasNoStatusAndItsByteCounts() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello(), null)
                Fixtures.connection.forEach { storage.received("s1", it) }
                storage.flush()
                val row = storage.searchExchanges(ExchangeFilter()).single()
                assertTrue(row.isConnection)
                assertEquals("CONNECT", row.method)
                assertEquals("push.example.com", row.host)
                assertNull(row.statusCode)
                assertEquals(false, row.failed)
                assertEquals(1_500L, row.endedAtMs!! - row.startedAtMs!!)
                assertEquals(ConnectionBytes(sent = 512, received = 2_048), storage.exchange(row.id)!!.connectionBytes)
                val sizes = storage.searchExchangeSizes(ExchangeFilter()).single()
                assertEquals(512L to 2_048L, sizes.requestBytes to sizes.responseBytes)
                assertEquals(1, storage.searchExchanges(ExchangeFilter(text = "PUSH.example.com:5223")).size)
                StatusClass.entries.forEach { assertEquals(0, storage.searchExchanges(ExchangeFilter(statusClass = it)).size) }
                storage.close(5.seconds)
            }
        }

    @Test
    fun connectionsGroupPerHostAndPort() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello(), null)
                Fixtures.connection.forEach { storage.received("s1", it) }
                storage.received("s1", envelope(connection("c2", "tcp://push.example.com:443")))
                storage.received("s1", envelope(connection("c3", "tcp://push.example.com:5223")))
                storage.received("s1", envelope(RequestFailed(request_id = "c3", error = "NSPOSIXErrorDomain 61: Connection refused")))
                storage.flush()
                val groups = storage.groupByEndpoint(ExchangeFilter())
                val tally = groups.endpoints.first()
                assertEquals(Endpoint("CONNECT", "push.example.com", ":5223"), tally.endpoint)
                assertEquals(2L, tally.count)
                assertEquals(1L, tally.failed)
                assertEquals(0.5, tally.errorRate)
                assertEquals(listOf(0L, 0L, 0L, 0L, 0L), tally.statusClasses)
                assertEquals(512L to 2_048L, tally.requestBytes to tally.responseBytes)
                assertEquals(1_500L, tally.durationStats!!.max)
                assertEquals(Endpoint("CONNECT", "push.example.com", ":443"), groups.endpoints[1].endpoint)
                val diff = diffEndpoints(EndpointGroups(emptyList(), 0, false), groups)
                assertEquals(groups.endpoints.map { it.endpoint }, diff.added.map { it.endpoint })
                storage.close(5.seconds)
            }
        }

    private fun connection(
        requestId: String,
        url: String,
    ) = RequestStarted(request_id = requestId, method = "CONNECT", url = url, capture_layer = CaptureLayer.CAPTURE_LAYER_CONNECTION)
}
