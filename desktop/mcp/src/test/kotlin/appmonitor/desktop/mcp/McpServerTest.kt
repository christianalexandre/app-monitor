package appmonitor.desktop.mcp

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    @Test
    fun clientListsRunsOverHttp() =
        withServer { running ->
            running.storage.connected("s1", hello(), null)
            running.storage.received("s1", started("r1"))
            running.storage.flush()

            val result = running.client { it.callTool(ListRuns.NAME, emptyMap()) }

            assertNotEquals(true, result.isError)
            val run =
                result.json["runs"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(running.storage.runId, run["id"]!!.jsonPrimitive.long)
            assertEquals(1, run["exchange_count"]!!.jsonPrimitive.long)
            assertEquals(
                "iPhone 17",
                run["sessions"]!!
                    .jsonArray
                    .single()
                    .jsonObject["device_name"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun statelessEndpoint() =
        withServer { running ->
            running.client { client ->
                assertEquals("app-monitor", client.serverVersion?.name)
                val capabilities = client.serverCapabilities!!
                assertEquals(false, capabilities.tools?.listChanged)
                assertNull(capabilities.resources)
                val tool = client.listTools().tools.single { it.name == ListRuns.NAME }
                assertTrue(tool.description!!.endsWith(DATA_NOTE))
                assertEquals(true, tool.annotations?.readOnlyHint)
            }

            val initialized = running.post()
            assertEquals(200, initialized.status)
            assertFalse("mcp-session-id" in initialized.headers, "no session: ${initialized.headers}")
            assertTrue(initialized.headers["content-type"]!!.startsWith("application/json"))

            val headers = mapOf("Host" to "127.0.0.1:${running.port}", "Authorization" to "Bearer ${running.access.token.value}")
            assertEquals(405, raw(running.port, "GET", McpServer.PATH, headers).status)
            assertEquals(405, raw(running.port, "DELETE", McpServer.PATH, headers).status)
        }
}
