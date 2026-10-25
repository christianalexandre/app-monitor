package appmonitor.desktop.mcp

import appmonitor.desktop.storage.ExchangeFilter
import appmonitor.desktop.storage.StorageHealth
import appmonitor.v1.Header
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ToCurlTest {
    private class Received(
        val method: String,
        val uri: String,
        val headers: List<Pair<String, String>>,
        val body: ByteString,
    )

    /** Runs [command] with `sh` against a local server, in place of the stored URL's port 1, and returns what it received. */
    private fun run(command: String): Received {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var received: Received? = null
        server.createContext("/") { exchange ->
            received =
                Received(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.flatMap { (name, values) -> values.map { name.lowercase() to it } },
                    exchange.requestBody.readBytes().toByteString(),
                )
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val process = ProcessBuilder("sh", "-c", command.replace(":1/", ":${server.address.port}/")).redirectErrorStream(true).start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "curl did not finish")
            val output = process.inputStream.readBytes().decodeToString()
            assertEquals(0, process.exitValue(), output)
        } finally {
            server.stop(0)
        }
        return received ?: error("the server received nothing")
    }

    private suspend fun Running.store(vararg events: com.squareup.wire.Message<*, *>): Long {
        storage.connected("s1", hello(), null)
        events.forEach { storage.received("s1", envelope(it)) }
        storage.flush()
        return storage.searchExchanges(ExchangeFilter()).last().id
    }

    private suspend fun Running.curl(id: Long) = client { it.callTool(ToCurl.NAME, mapOf("id" to id)) }.text

    @Test
    fun theCommandReproducesTheStoredRequest() =
        withServer { running ->
            val body = "name=it's \"quoted\"\nline two \$HOME `x`"
            val headers =
                listOf(
                    Header("Content-Type", "text/plain"),
                    Header("Authorization", "Bearer it's"),
                    Header("X-Dup", "1"),
                    Header("X-Dup", "2"),
                    Header("X-Empty", ""),
                )
            val id =
                running.store(
                    request("r1", "PATCH", "http://127.0.0.1:1/items/1?q=a%20b&x='y'", headers, body.encodeUtf8()),
                    response("r1"),
                )
            val command = running.curl(id)
            assertTrue(command.startsWith("curl --globoff -X 'PATCH' 'http://127.0.0.1:1/items/1?q=a%20b&x='\\''y'\\'''"), command)
            val received = run(command)
            assertEquals("PATCH", received.method)
            assertEquals("/items/1?q=a%20b&x='y'", received.uri)
            assertEquals(body.encodeUtf8(), received.body)
            assertEquals(listOf("1", "2"), received.headers.filter { it.first == "x-dup" }.map { it.second })
            for (header in listOf("content-type" to "text/plain", "authorization" to "Bearer it's", "x-empty" to "")) {
                assertTrue(header in received.headers, "$header in ${received.headers}")
            }
        }

    @Test
    fun aBinaryBodyIsPipedThroughBase64() =
        withServer { running ->
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x0A, 0x27, 0xFF.toByte()).toByteString()
            val id = running.store(request("r1", "PUT", "http://127.0.0.1:1/upload", listOf(Header("Content-Type", "image/png")), bytes))
            val command = running.curl(id)
            assertTrue(command.startsWith("printf %s '${bytes.base64()}' | base64 -d | curl"), command)
            val received = run(command)
            assertEquals("PUT", received.method)
            assertEquals(bytes, received.body)
        }

    @Test
    fun aTextBodyWithNulGoesTheBinaryWay() =
        withServer { running ->
            val bytes = "a\u0000b".encodeUtf8()
            val id = running.store(request("r1", "POST", "http://127.0.0.1:1/", emptyList(), bytes))
            assertEquals(bytes, run(running.curl(id)).body)
        }

    @Test
    fun getWithoutBodyAndHead() =
        withServer { running ->
            val get = running.store(request("r1", url = "http://127.0.0.1:1/a"))
            val received = run(running.curl(get))
            assertEquals("GET", received.method)
            assertEquals("application/json", received.headers.single { it.first == "accept" }.second)
            assertEquals(0, received.body.size)
            val head = running.store(request("r2", "HEAD", "http://127.0.0.1:1/b"))
            assertEquals("HEAD", run(running.curl(head)).method)
        }

    @Test
    fun aBodyCutByTheDeviceIsSaidFirst() =
        withServer { running ->
            val id =
                running.store(
                    request("r1", "POST", "http://127.0.0.1:1/", emptyList(), "abc".encodeUtf8(), bodyTruncated = true, bodySize = 3_000),
                )
            val command = running.curl(id)
            assertEquals("# body truncated by the device: 3 of 3000 bytes", command.lines().first())
            assertEquals("abc".encodeUtf8(), run(command).body)
        }

    @Test
    fun storedFramingHeadersAreLeftToCurl() =
        withServer { running ->
            val headers = listOf(Header("Content-Length", "3000"), Header("Transfer-Encoding", "chunked"), Header("X-Kept", "1"))
            val id =
                running.store(
                    request("r1", "POST", "http://127.0.0.1:1/", headers, "abc".encodeUtf8(), bodyTruncated = true, bodySize = 3_000),
                )
            val command = running.curl(id)
            assertTrue("Content-Length" !in command && "Transfer-Encoding" !in command, command)
            val received = run(command)
            assertEquals("abc".encodeUtf8(), received.body)
            assertEquals("3", received.headers.single { it.first == "content-length" }.second)
            assertEquals("1", received.headers.single { it.first == "x-kept" }.second)
        }

    @Test
    fun anEmptyBodyKeepsAStoredZeroLength() =
        withServer { running ->
            val id = running.store(request("r1", "POST", "http://127.0.0.1:1/", listOf(Header("Content-Length", "0")), ByteString.EMPTY))
            val received = run(running.curl(id))
            assertEquals("POST", received.method)
            assertEquals("0", received.headers.single { it.first == "content-length" }.second)
            assertEquals(0, received.body.size)
        }

    @Test
    fun aHeadWithAStoredBodyLeavesItOut() =
        withServer { running ->
            val headers = listOf(Header("Content-Length", "3"))
            val id = running.store(request("r1", "HEAD", "http://127.0.0.1:1/h", headers, "abc".encodeUtf8()))
            val command = running.curl(id)
            assertEquals("# body of 3 bytes left out: curl sends none with --head", command.lines().first())
            val received = run(command)
            assertEquals("HEAD", received.method)
            assertEquals(0, received.body.size)
        }

    @Test
    fun lostWritesAreACommentLine() =
        withServer(health = { StorageHealth(notSaved = 2, lastError = "disk full") }) { running ->
            val id = running.store(request("r1", url = "http://127.0.0.1:1/"))
            val command = running.curl(id)
            assertEquals("# warning: 2 events were not saved: disk full", command.lines().first())
            assertEquals("GET", run(command).method)
        }

    @Test
    fun unknownIdMissingIdAndClosingStore() =
        withServer { running ->
            running.client { client ->
                val unknown = client.callTool(ToCurl.NAME, mapOf("id" to 999))
                assertEquals(true, unknown.isError)
                assertEquals("no exchange with id 999", unknown.text)
                assertEquals("id is required", client.callTool(ToCurl.NAME, emptyMap()).text)
            }
            running.storage.close(5.seconds)
            assertEquals(CLOSING, running.client { it.callTool(ToCurl.NAME, mapOf("id" to 1)) }.text, "not \"no exchange with id 1\"")
        }

    @Test
    fun quotes() {
        assertEquals("'a'\\''b'", ToCurl.quote("a'b"))
        assertEquals("''", ToCurl.quote(""))
    }
}
