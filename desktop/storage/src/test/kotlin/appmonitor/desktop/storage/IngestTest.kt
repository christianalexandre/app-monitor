package appmonitor.desktop.storage

import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IngestTest {
    @Test
    fun roundTripsEveryEvent() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            // Field 15 is unknown to this desktop; Wire keeps it in the stored meta.
            val unknown = Buffer().writeByte(15 shl 3).writeByte(42).readByteString()
            val request = RequestStarted.ADAPTER.decode(Buffer().write(started("r1", body = text(4_000)).encode()).write(unknown))
            val response =
                ResponseReceived(
                    request_id = "r1",
                    status_code = 201,
                    headers = listOf(Header("Content-Type", "application/json")),
                    body = random(300),
                    body_truncated = true,
                    body_size = 9_000,
                )
            val frame =
                WebSocketFrame(
                    request_id = "r1",
                    direction = WebSocketFrame.Direction.DIRECTION_RECEIVED,
                    opcode = WebSocketFrame.Opcode.OPCODE_TEXT,
                    payload = "hello".encodeUtf8(),
                    payload_truncated = true,
                    payload_size = 10,
                )
            runBlocking {
                storage.connected("s1", hello(), "192.168.1.20")
                storage.received("s1", envelope(request, atMs = 1_000))
                storage.received("s1", envelope(response, atMs = 1_250))
                storage.received("s1", envelope(frame, atMs = 1_300))
                storage.received("s1", envelope(RequestFailed("r2", "java.net.SocketTimeoutException: timeout"), atMs = 2_000))
                storage.disconnected("s1")
                storage.flush()

                val session = storage.sessions(storage.runId).single()
                assertEquals(hello(), session.hello)
                assertEquals("192.168.1.20", session.peerAddress)
                assertTrue(session.disconnectedAtMs != null)

                val (first, failed) = storage.exchangesOfRun(storage.runId)
                val detail = storage.exchange(first.id)!!
                assertEquals(request, detail.request, "byte for byte, unknown field included")
                assertTrue(detail.request!!.unknownFields.size > 0)
                assertEquals(response, detail.response)
                assertEquals("api.example.com", first.host)
                assertEquals(201, first.statusCode)
                assertEquals(250, first.endedAtMs!! - first.startedAtMs!!)
                assertEquals(1, first.frameCount)
                val stored = storage.frames(first.id).single()
                assertEquals(frame.payload, stored.payload)
                assertEquals(frame.direction, stored.direction)
                assertEquals(frame.opcode, stored.opcode)
                assertEquals(1_300, stored.atMs)
                assertTrue(stored.payloadTruncated)
                assertEquals(10, stored.payloadSize)

                assertTrue(failed.failed)
                assertEquals("java.net.SocketTimeoutException: timeout", failed.error)
                storage.close(5.seconds)
            }
        }

    @Test
    fun eventBeforeStartCreatesExchange() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                storage.connected("s1", hello(), null)
                storage.received("s1", envelope(ResponseReceived(request_id = "late", status_code = 204)))
                storage.flush()
                val exchange = storage.exchangesOfRun(storage.runId).single()
                assertEquals("late", exchange.requestId)
                assertEquals(204, exchange.statusCode)
                assertNull(exchange.method)
                assertNull(storage.exchange(exchange.id)!!.request)

                storage.received("s1", envelope(started("late")))
                storage.flush()
                assertEquals("GET", storage.exchangesOfRun(storage.runId).single().method, "the same row, completed")
                storage.close(5.seconds)
            }
        }

    @Test
    fun keepsWhatItDoesNotDecode() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            val kept =
                listOf(
                    Envelope(channel = "logs", type = "LogLine", payload = "line".encodeUtf8(), timestamp_ms = 1),
                    Envelope(channel = "network", type = "GrpcCall", payload = "call".encodeUtf8(), timestamp_ms = 2),
                    Envelope(channel = "network", type = "RequestStarted", payload = ByteString.of(0x0a, 0x7f), timestamp_ms = 3),
                )
            runBlocking {
                storage.connected("s1", hello(), null)
                kept.forEach { storage.received("s1", it) }
                storage.flush()
                assertTrue(storage.exchangesOfRun(storage.runId).isEmpty())
                storage.close(5.seconds)
            }
            assertEquals(listOf("logs", "network", "network"), dir.queryStrings("SELECT channel FROM raw_envelopes ORDER BY id"))
            assertEquals(listOf("LogLine", "GrpcCall", "RequestStarted"), dir.queryStrings("SELECT type FROM raw_envelopes ORDER BY id"))
            assertEquals(kept.sumOf { it.payload.size }.toLong(), dir.queryLong("SELECT sum(length(payload)) FROM raw_envelopes"))
        }

    @Test
    fun picksCodecPerBody() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            val compressible = text(64_000)
            val incompressible = random(64_000)
            runBlocking {
                storage.connected("s1", hello(), null)
                storage.received("s1", envelope(started("text", body = compressible)))
                storage.received("s1", envelope(started("noise", body = incompressible)))
                storage.received("s1", envelope(started("empty")))
                storage.flush()
                val (text, noise) = storage.exchangesOfRun(storage.runId)
                assertEquals(compressible, storage.exchange(text.id)!!.request!!.body)
                assertEquals(incompressible, storage.exchange(noise.id)!!.request!!.body)
                storage.close(5.seconds)
            }
            assertEquals(listOf("deflate", "raw"), dir.queryStrings("SELECT codec FROM bodies ORDER BY exchange_ref"))
            assertTrue(dir.queryLong("SELECT length(data) FROM bodies WHERE codec = 'deflate'") < compressible.size / 10)
            assertEquals(64_000L, dir.queryLong("SELECT min(size) FROM bodies"), "size is before compression")
            assertEquals(2, dir.queryLong("SELECT count(*) FROM bodies"), "an empty body has no row")
        }
}
