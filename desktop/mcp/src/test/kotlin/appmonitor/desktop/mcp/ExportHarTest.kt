package appmonitor.desktop.mcp

import appmonitor.desktop.storage.StorageHealth
import appmonitor.v1.Header
import appmonitor.v1.RequestFailed
import appmonitor.v1.WebSocketFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ExportHarTest {
    private suspend fun Running.store(vararg events: Pair<com.squareup.wire.Message<*, *>, Long>) {
        storage.connected("s1", hello(), null)
        events.forEach { (event, at) -> storage.received("s1", envelope(event, at)) }
        storage.flush()
    }

    private suspend fun Running.export(arguments: Map<String, Any?> = emptyMap()) = client { it.callTool(ExportHar.NAME, arguments) }

    @Test
    fun everyHar12RequiredField() =
        withServer { running ->
            running.store(
                request(
                    "r1",
                    method = "POST",
                    url = "https://api.example.com/items?q=a%20b&flag#top",
                    headers = listOf(Header("Content-Type", "application/json"), Header("Authorization", "Bearer secret")),
                    body = """{"a":1}""".encodeUtf8(),
                ) to 1_000L,
                response("r1", 201, body = """{"ok":true}""".encodeUtf8()) to 1_250L,
                request("r2") to 2_000L,
                RequestFailed(request_id = "r2", error = "timeout") to 2_100L,
                request("r3") to 3_000L,
            )
            val result = running.export()
            val har = result.json
            assertHar12(har)
            val log = har.obj("log")
            assertEquals("App Monitor", log.obj("creator").str("name"))
            assertEquals("test", log.obj("creator").str("version"))
            assertNull(log["comment"])
            val entries = log["entries"]!!.jsonArray.map { it.jsonObject }
            assertEquals(3, entries.size)

            val first = entries[0]
            assertEquals("1970-01-01T00:00:01.000Z", first.str("startedDateTime"))
            assertEquals(250, first["time"]!!.jsonPrimitive.long)
            assertEquals(250, first.obj("timings")["wait"]!!.jsonPrimitive.long)
            val request = first.obj("request")
            assertEquals("POST", request.str("method"))
            assertEquals(listOf("Content-Type" to "application/json", "Authorization" to "Bearer secret"), request.pairs("headers"))
            assertEquals(listOf("q" to "a b", "flag" to ""), request.pairs("queryString"))
            assertEquals("""{"a":1}""", request.obj("postData").str("text"))
            assertEquals("application/json", request.obj("postData").str("mimeType"))
            assertEquals(7, request["bodySize"]!!.jsonPrimitive.long)
            val response = first.obj("response")
            assertEquals(201, response["status"]!!.jsonPrimitive.long)
            assertEquals("""{"ok":true}""", response.obj("content").str("text"))
            assertEquals(11, response.obj("content")["size"]!!.jsonPrimitive.long)

            assertEquals(0, entries[1].obj("response")["status"]!!.jsonPrimitive.long)
            assertEquals("failed: timeout", entries[1].str("comment"))
            assertNull(entries[1].obj("request")["postData"], "no body, no postData")
            assertEquals(0, entries[2]["time"]!!.jsonPrimitive.long, "in flight")
            assertEquals("no end time stored", entries[2].str("comment"))
        }

    @Test
    fun binaryAndCutBodies() =
        withServer { running ->
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte()).toByteString()
            val big = "x".repeat(20_000)
            running.store(
                request("r1", body = png, headers = listOf(Header("Content-Type", "image/png"))) to 1_000L,
                response("r1", headers = listOf(Header("Content-Type", "image/png")), body = png) to 1_010L,
                request("r2") to 2_000L,
                response("r2", body = big.encodeUtf8(), bodyTruncated = true, bodySize = 3_000_000) to 2_010L,
            )
            val capped = running.export().json.entries()
            assertHar12(running.export().json)
            val content = capped[0].obj("response").obj("content")
            assertEquals("base64", content.str("encoding"))
            assertEquals(png, content.str("text").decodeBase64())
            assertEquals("image/png", content.str("mimeType"))
            val postData = capped[0].obj("request").obj("postData")
            assertEquals(png, postData.str("text").decodeBase64())
            assertEquals("base64", postData.str("_encoding"))
            val cut = capped[1].obj("response").obj("content")
            assertEquals(ExportHar.CAP.toInt(), cut.str("text").length)
            assertEquals(3_000_000, cut["size"]!!.jsonPrimitive.long)
            assertEquals(
                "truncated by the device: 20000 of 3000000 bytes stored; truncated by the export: 16384 of 20000 stored bytes",
                cut.str("comment"),
            )

            val full =
                running
                    .export(mapOf("bodies" to "full"))
                    .json
                    .entries()[1]
                    .obj("response")
                    .obj("content")
            assertEquals(big, full.str("text"))
            assertEquals("truncated by the device: 20000 of 3000000 bytes stored", full.str("comment"))

            val none = running.export(mapOf("bodies" to "none")).json
            assertHar12(none)
            val noneContent = none.entries()[1].obj("response").obj("content")
            assertNull(noneContent["text"])
            assertEquals(3_000_000, noneContent["size"]!!.jsonPrimitive.long)
        }

    @Test
    fun framesAsWebSocketMessages() =
        withServer { running ->
            fun frame(
                payload: okio.ByteString,
                direction: WebSocketFrame.Direction,
                opcode: WebSocketFrame.Opcode,
            ) = WebSocketFrame(request_id = "ws", direction = direction, opcode = opcode, payload = payload)
            val bytes = byteArrayOf(0x00, 0xFF.toByte()).toByteString()
            running.store(
                request("ws", url = "wss://echo.example.com/") to 1_000L,
                response("ws", 101) to 1_100L,
                frame("hi".encodeUtf8(), WebSocketFrame.Direction.DIRECTION_SENT, WebSocketFrame.Opcode.OPCODE_TEXT) to 1_500L,
                frame(bytes, WebSocketFrame.Direction.DIRECTION_RECEIVED, WebSocketFrame.Opcode.OPCODE_BINARY) to 1_750L,
                frame(okio.ByteString.EMPTY, WebSocketFrame.Direction.DIRECTION_SENT, WebSocketFrame.Opcode.OPCODE_CLOSE) to 2_000L,
            )
            val har = running.export().json
            assertHar12(har)
            val messages =
                har
                    .entries()
                    .single()["_webSocketMessages"]!!
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf("send", "receive", "send"), messages.map { it.str("type") })
            assertEquals(listOf(1L, 2L, 8L), messages.map { it["opcode"]!!.jsonPrimitive.long })
            assertEquals(listOf(1.5, 1.75, 2.0), messages.map { it["time"]!!.jsonPrimitive.double })
            assertEquals("hi", messages[0].str("data"))
            assertEquals(bytes, messages[1].str("data").decodeBase64())
            assertEquals("", messages[2].str("data"))
        }

    @Test
    fun filterAndMaxEntries() =
        withServer { running ->
            running.store(*Array(5) { request("r$it", method = if (it % 2 == 0) "GET" else "PUT") to 1_000L + it })
            val gets = running.export(mapOf("method" to "GET")).json.entries()
            assertEquals(3, gets.size)
            assertTrue(gets.all { it.obj("request").str("method") == "GET" })
            val two = running.export(mapOf("max_entries" to 2)).json.obj("log")
            assertEquals(listOf("https://api.example.com/items/r0", "https://api.example.com/items/r1"), two.urls())
            assertEquals("first 2 matching exchanges; more matched", two.str("comment"))
            running.client { client ->
                for (arguments in listOf(mapOf("max_entries" to 1_001), mapOf("bodies" to "some"))) {
                    val result = client.callTool(ExportHar.NAME, arguments)
                    assertEquals(true, result.isError, "$arguments")
                    assertTrue(result.text.startsWith(arguments.keys.single()), result.text)
                }
            }
        }

    @Test
    fun past32MiBIsRefused() =
        withServer { running ->
            running.store(request("r1") to 1_000L, response("r1", body = "y".repeat(1_000).encodeUtf8()) to 1_001L)
            val error = assertFailsWith<ToolError> { ExportHar.call(running.storage, null, "test", maxText = 1_000) }
            assertEquals("the HAR is past 0 MiB: narrow the filter or use bodies: none", error.message)
            ExportHar.call(running.storage, mapOf("bodies" to JsonPrimitive("none")).let(::JsonObject), "test", maxText = 1_000)
            assertEquals(32L * 1024 * 1024, ExportHar.MAX_TEXT)
        }

    @Test
    fun theCapCountsUtf8Bytes() =
        withServer { running ->
            running.store(request("r1") to 1_000L, response("r1", body = "é".repeat(400).encodeUtf8()) to 1_001L)
            val chars =
                ExportHar
                    .call(running.storage, null, "test")
                    .toString()
                    .length
                    .toLong()
            assertFailsWith<ToolError> { ExportHar.call(running.storage, null, "test", maxText = chars) }
        }

    @Test
    fun lostWritesAreAWarningInsideTheLog() =
        withServer(health = { StorageHealth(notSaved = 3, lastError = "disk I/O error") }) { running ->
            running.store(request("r1") to 1_000L)
            val har = running.export().json
            assertHar12(har)
            assertEquals(
                listOf("3 events were not saved: disk I/O error"),
                har.obj("log")["_warnings"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(1, har.entries().size)
        }

    @Test
    fun closingStoreIsAnError() =
        withServer { running ->
            running.store(request("r1") to 1_000L)
            running.storage.close(5.seconds)
            assertEquals(CLOSING, running.client { it.callTool(ExportHar.NAME, emptyMap()) }.text)
        }

    private fun JsonObject.obj(name: String) = this[name]!!.jsonObject

    private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content

    private fun JsonObject.pairs(name: String) = (this[name] as JsonArray).map { it.jsonObject.str("name") to it.jsonObject.str("value") }

    private fun JsonObject.entries() = obj("log")["entries"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.urls() = this["entries"]!!.jsonArray.map { it.jsonObject.obj("request").str("url") }
}
