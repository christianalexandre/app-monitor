package appmonitor.desktop.mcp

import appmonitor.v1.Header
import appmonitor.v1.WebSocketFrame
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetRequestTest {
    private suspend fun Running.store(vararg events: com.squareup.wire.Message<*, *>): Long {
        storage.connected("s1", hello(), null)
        events.forEach { storage.received("s1", envelope(it)) }
        storage.flush()
        return storage.searchExchanges(appmonitor.desktop.storage.ExchangeFilter()).last().id
    }

    private suspend fun Running.get(arguments: Map<String, Any?>) = client { it.callTool(GetRequest.NAME, arguments) }.json

    @Test
    fun headersAndBodiesAsStored() =
        withServer { running ->
            val headers =
                listOf(
                    Header("Authorization", "Bearer secret"),
                    Header("Content-Type", "text/plain"),
                    Header("X-Dup", "1"),
                    Header("X-Dup", "2"),
                )
            val id =
                running.store(
                    request("r1", method = "POST", headers = headers, body = "hello".encodeUtf8()),
                    response("r1", 201, body = """{"ok":true}""".encodeUtf8()),
                )
            val result = running.get(mapOf("id" to id))
            assertEquals(id, result["id"]!!.jsonPrimitive.long)
            assertEquals(201, result["status_code"]!!.jsonPrimitive.long)
            val request = result["request"]!!.jsonObject
            assertEquals("POST", request["method"]!!.jsonPrimitive.content)
            assertEquals(
                headers.map { it.name to it.value_ },
                request["headers"]!!.jsonArray.map { it.jsonObject.let { h -> h.str("name") to h.str("value") } },
                "in order, duplicates and the unmasked value kept",
            )
            val body = request["body"]!!.jsonObject
            assertEquals("utf8", body.str("encoding"))
            assertEquals("hello", body.str("text"))
            assertEquals("text/plain", body.str("content_type"))
            assertEquals(5, body.num("stored_bytes"))
            assertEquals(false, body.flag("truncated_by_tool"))
            assertEquals(JsonNull, body["original_size"])
            val response = result["response"]!!.jsonObject
            assertEquals("""{"ok":true}""", response["body"]!!.jsonObject.str("text"))
            assertEquals("application/json", response["body"]!!.jsonObject.str("content_type"))

            assertEquals(JsonNull, running.get(mapOf("id" to id, "part" to "request"))["response"])
            assertEquals(JsonNull, running.get(mapOf("id" to id, "part" to "response"))["request"])
        }

    @Test
    fun aBodyPastTheCapIsReadWithBodyOffset() =
        withServer { running ->
            val text = "0123456789".repeat(2_000) // 20 000 bytes, past the 16 384 default
            val id = running.store(request("r1"), response("r1", body = text.encodeUtf8()))
            val first = running.get(mapOf("id" to id, "part" to "response"))["response"]!!.jsonObject["body"]!!.jsonObject
            assertEquals(16_384, first.num("returned_bytes"))
            assertEquals(20_000, first.num("stored_bytes"))
            assertEquals(true, first.flag("truncated_by_tool"))
            val rest =
                running
                    .get(mapOf("id" to id, "part" to "response", "body_offset" to 16_384))["response"]!!
                    .jsonObject["body"]!!
                    .jsonObject
            assertEquals(16_384, rest.num("offset"))
            assertEquals(3_616, rest.num("returned_bytes"))
            assertEquals(false, rest.flag("truncated_by_tool"))
            assertEquals(text, first.str("text") + rest.str("text"))
        }

    @Test
    fun aBodyCutByTheDevice() =
        withServer { running ->
            val id = running.store(request("r1"), response("r1", body = "abc".encodeUtf8(), bodyTruncated = true, bodySize = 3_000_000))
            val body = running.get(mapOf("id" to id))["response"]!!.jsonObject["body"]!!.jsonObject
            assertEquals(true, body.flag("truncated_by_device"))
            assertEquals(false, body.flag("truncated_by_tool"))
            assertEquals(3_000_000, body.num("original_size"))
            assertEquals(3, body.num("stored_bytes"))
        }

    @Test
    fun aBinaryBodyIsBase64AndAnEmptyOneIsNull() =
        withServer { running ->
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte()).toByteString()
            val id = running.store(request("r1"), response("r1", headers = listOf(Header("Content-Type", "image/png")), body = bytes))
            val result = running.get(mapOf("id" to id))
            val body = result["response"]!!.jsonObject["body"]!!.jsonObject
            assertEquals("base64", body.str("encoding"))
            assertEquals(bytes, body.str("base64").decodeBase64())
            assertNull(body["text"])
            assertEquals("image/png", body.str("content_type"))
            assertEquals(JsonNull, result["request"]!!.jsonObject["body"], "no request body stored")
        }

    @Test
    fun aUtf8SliceCutMidCharacterEndsAtTheBoundary() =
        withServer { running ->
            val text = "aé€😀b" // 1 + 2 + 3 + 4 + 1 bytes
            val id = running.store(request("r1"), response("r1", body = text.encodeUtf8()))
            for ((max, expected) in listOf(2 to "a", 4 to "aé", 6 to "aé€", 9 to "aé€", 10 to "aé€😀")) {
                val body = running.get(mapOf("id" to id, "body_max_bytes" to max))["response"]!!.jsonObject["body"]!!.jsonObject
                assertEquals("utf8", body.str("encoding"), "max $max")
                assertEquals(expected, body.str("text"), "max $max")
                assertEquals(expected.encodeUtf8().size.toLong(), body.num("returned_bytes"))
                assertEquals(true, body.flag("truncated_by_tool"))
            }
            val inside =
                running
                    .get(
                        mapOf("id" to id, "body_offset" to 2, "body_max_bytes" to 1),
                    )["response"]!!
                    .jsonObject["body"]!!
                    .jsonObject
            assertEquals("base64", inside.str("encoding"), "a slice starting inside a character is not text")
            assertEquals(1, inside.num("returned_bytes"))
        }

    @Test
    fun framesByPageAndByByteBudget() =
        withServer { running ->
            fun frame(payload: String) =
                WebSocketFrame(
                    request_id = "ws",
                    direction = WebSocketFrame.Direction.DIRECTION_SENT,
                    opcode = WebSocketFrame.Opcode.OPCODE_TEXT,
                    payload = payload.encodeUtf8(),
                )
            val id = running.store(request("ws", url = "wss://echo.example.com/"), *Array(5) { frame("x".repeat(10) + it) })
            val first = running.get(mapOf("id" to id, "frames_limit" to 2))["frames"]!!.jsonObject
            val items = first["items"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("xxxxxxxxxx0", "xxxxxxxxxx1"), items.map { it["payload"]!!.jsonObject.str("text") })
            assertEquals("sent", items[0].str("direction"))
            assertEquals("text", items[0].str("opcode"))
            val next = first["next_after_id"]!!.jsonPrimitive.long
            val rest = running.get(mapOf("id" to id, "frames_after_id" to next, "frames_limit" to 10))["frames"]!!.jsonObject
            assertEquals(3, rest["items"]!!.jsonArray.size)
            assertEquals(JsonNull, rest["next_after_id"])

            val budget = running.get(mapOf("id" to id, "body_max_bytes" to 15))["frames"]!!.jsonObject
            assertEquals(2, budget["items"]!!.jsonArray.size, "stops once 15 bytes are reached, after the second frame")
            assertTrue(budget["next_after_id"] != JsonNull)
            val tiny = running.get(mapOf("id" to id, "body_max_bytes" to 1))["frames"]!!.jsonObject
            assertEquals(1, tiny["items"]!!.jsonArray.size, "at least one frame")

            assertEquals(
                0,
                running
                    .get(mapOf("id" to id, "frames_limit" to 0))["frames"]!!
                    .jsonObject["items"]!!
                    .jsonArray.size,
            )
        }

    @Test
    fun anUnknownIdOrABadArgumentIsAnError() =
        withServer { running ->
            running.client { client ->
                val unknown = client.callTool(GetRequest.NAME, mapOf("id" to 999))
                assertEquals(true, unknown.isError)
                assertEquals("no exchange with id 999", unknown.text)
                assertEquals("id is required", client.callTool(GetRequest.NAME, emptyMap()).text)
                for (arguments in listOf(mapOf("body_max_bytes" to 262_145), mapOf("part" to "body"), mapOf("frames_limit" to 201))) {
                    val result = client.callTool(GetRequest.NAME, arguments + ("id" to 1))
                    assertEquals(true, result.isError, "$arguments")
                    assertTrue(result.text.startsWith(arguments.keys.single()), result.text)
                }
            }
        }

    private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content

    private fun JsonObject.num(name: String) = this[name]!!.jsonPrimitive.long

    private fun JsonObject.flag(name: String) = this[name]!!.jsonPrimitive.boolean
}
