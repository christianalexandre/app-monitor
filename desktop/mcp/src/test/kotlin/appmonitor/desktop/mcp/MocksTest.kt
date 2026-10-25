package appmonitor.desktop.mcp

import appmonitor.desktop.storage.MockSource
import appmonitor.desktop.storage.MockWrite
import appmonitor.v1.Header
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class MocksTest {
    @Test
    fun aRuleFromMcpIsStoredPendingAndReachesNoDeviceUntilConfirmed() =
        withServer { running ->
            val mock =
                running.client { client ->
                    client
                        .callTool(
                            CreateMock.NAME,
                            mapOf(
                                "app_id" to APP,
                                "name" to " Items down ",
                                "method" to "GET",
                                "host" to "api.example.com",
                                "path" to "/v1/items/*",
                                "delay_ms" to 250,
                                "status_code" to 503,
                                "headers" to listOf(mapOf("name" to "Retry-After", "value" to "5")),
                                "body" to "{\"error\":\"down\"}",
                            ),
                        ).json["mock"]!!
                        .jsonObject
                }
            val id = mock["id"]!!.jsonPrimitive.long
            assertEquals(true, mock["pending"]!!.jsonPrimitive.boolean)
            assertEquals("mcp", mock["source"]!!.jsonPrimitive.content)
            assertEquals("Items down", mock["name"]!!.jsonPrimitive.content)
            assertEquals(16, mock["response"]!!.jsonObject["body_bytes"]!!.jsonPrimitive.long)

            val stored = running.storage.mocks().single()
            assertEquals(MockSource.MCP to true, stored.source to stored.pending)
            val rule =
                MockRule(
                    id = id,
                    method = "GET",
                    host = "api.example.com",
                    path = "/v1/items/*",
                    delay_ms = 250,
                    response = MockResponse(503, listOf(Header("Retry-After", "5")), "{\"error\":\"down\"}".encodeUtf8()),
                )
            assertEquals(rule, stored.rule)
            assertEquals(emptyList(), running.storage.observeActiveMocks(APP).first(), "pending: no device gets it")

            assertIs<MockWrite.Saved>(running.storage.confirmMock(id))
            assertEquals(listOf(rule), running.storage.observeActiveMocks(APP).first())
        }

    @Test
    fun aFailureRuleWithAnyMethodAndABinaryBody() =
        withServer { running ->
            running.client { client ->
                val failure =
                    client
                        .callTool(
                            CreateMock.NAME,
                            mapOf(
                                "app_id" to APP,
                                "name" to "offline",
                                "host" to "api.example.com",
                                "path" to "/",
                                "failure_message" to "offline",
                            ),
                        ).json["mock"]!!
                        .jsonObject
                assertEquals(JsonNull, failure["method"])
                assertEquals(JsonNull, failure["response"])
                assertEquals("offline", failure["failure_message"]!!.jsonPrimitive.content)

                client.callTool(
                    CreateMock.NAME,
                    mapOf(
                        "app_id" to APP,
                        "name" to "png",
                        "host" to "h",
                        "path" to "/p",
                        "status_code" to 200,
                        "body_base64" to "AAEC/w==",
                    ),
                )
            }
            val (offline, png) = running.storage.mocks()
            assertEquals(MockFailure("offline"), offline.rule.failure)
            assertEquals("", offline.rule.method)
            assertEquals(byteArrayOf(0, 1, 2, -1).toByteString(), png.rule.response!!.body)
        }

    @Test
    fun badArgumentsAreErrorsAndWriteNothing() =
        withServer { running ->
            val base = mapOf("app_id" to APP, "name" to "n", "host" to "h", "path" to "/p")
            val cases =
                mapOf(
                    base - "app_id" + ("status_code" to 200) to "app_id is required and not blank",
                    base + ("name" to " ") + ("status_code" to 200) to "name is required and not blank",
                    base - "host" + ("status_code" to 200) to "host is required and not blank",
                    base + ("path" to "p") + ("status_code" to 200) to "path must start with /",
                    base to "give status_code or failure_message, not both",
                    base + ("status_code" to 200) + ("failure_message" to "x") to "give status_code or failure_message, not both",
                    base + ("status_code" to 99) to "status_code must be an integer from 100 to 599",
                    base + ("failure_message" to "x") + ("body" to "b") to "headers and body go with status_code",
                    base + ("status_code" to 200) + ("body" to "b") + ("body_base64" to "Yg==") to "give body or body_base64, not both",
                    base + ("status_code" to 200) + ("body_base64" to "%%") to "body_base64 is not base64",
                    base + ("status_code" to 200) + ("headers" to listOf(mapOf("name" to "A"))) to
                        "each header needs a name and a value, as strings",
                    base + ("status_code" to 200) + ("delay_ms" to -1) to "delay_ms must be an integer from 0 to ${Long.MAX_VALUE}",
                )
            running.client { client ->
                for ((arguments, message) in cases) {
                    val result = client.callTool(CreateMock.NAME, arguments)
                    assertEquals(true, result.isError, "$arguments")
                    assertEquals(message, result.text, "$arguments")
                }
            }
            assertEquals(emptyList(), running.storage.mocks())
        }

    @Test
    fun aCreateWhileTheStoreClosesIsAnError() =
        withServer { running ->
            running.storage.close(5.seconds)
            val result =
                running.client {
                    it.callTool(CreateMock.NAME, mapOf("app_id" to APP, "name" to "n", "host" to "h", "path" to "/", "status_code" to 200))
                }
            assertEquals(true, result.isError)
            assertEquals(CLOSING, result.text)
        }

    private companion object {
        const val APP = "dev.appmonitor.sample"
    }
}
