package appmonitor.desktop.app

import appmonitor.desktop.app.store.McpController
import appmonitor.desktop.app.store.McpStatus
import appmonitor.desktop.app.ui.claudeMcpAdd
import appmonitor.desktop.mcp.McpAccess
import appmonitor.desktop.mcp.McpServer
import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val INITIALIZE =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
        """"clientInfo":{"name":"raw","version":"1"}}}"""

class McpWiringTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun noServerWithoutStorage() {
        withDataDir { dir ->
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(Storage.FILE_NAME), "not a database, long enough to fill a header ".repeat(4))

            assertFails { openServices(dir) {} }
        }
    }

    @Test
    fun portTakenIsAStatus() =
        withStorage { storage ->
            ServerSocket(0, 0, loopback).use { taken ->
                val mcp = McpController(storage, runBlocking { McpAccess.open(storage) }, taken.localPort)

                runBlocking { mcp.start() }

                assertEquals(McpStatus.PortTaken(taken.localPort), mcp.status.value)
                runBlocking { mcp.close() }
            }
        }

    @Test
    fun stopsBeforeTheStoreCloses() =
        withStorage { storage ->
            val mcp = McpController(storage, runBlocking { McpAccess.open(storage) }, 0)
            runBlocking { mcp.start() }
            val port = assertIs<McpStatus.Running>(mcp.status.value).port
            val order = mutableListOf<String>()

            runBlocking {
                stopAll(mcp::close, { order += "server" }) {
                    order += "storage"
                    assertEquals(McpStatus.Off, mcp.status.value)
                    ServerSocket(port, 0, loopback).close()
                }
            }

            assertEquals(listOf("server", "storage"), order)
        }

    @Test
    fun stopAllClosesTheStoreWhenAStopFails() {
        val order = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            runBlocking { stopAll({ error("mcp") }, { order += "server" }) { order += "storage" } }
        }

        assertEquals(listOf("server", "storage"), order)
    }

    @Test
    fun switchStartsAndStops() =
        withStorage { storage ->
            val access = runBlocking { McpAccess.open(storage) }
            val mcp = McpController(storage, access, 0)
            runBlocking { mcp.start() }
            val port = assertIs<McpStatus.Running>(mcp.status.value).port
            assertEquals(200, initialize(port, access.token.value))

            runBlocking {
                mcp.setEnabled(false)
                storage.flush()
            }
            assertEquals(McpStatus.Off, mcp.status.value)
            ServerSocket(port, 0, loopback).close()

            val relaunched = McpController(storage, access, 0)
            runBlocking { relaunched.start() }
            assertEquals(false, relaunched.enabled.value)
            assertEquals(McpStatus.Off, relaunched.status.value)

            runBlocking { relaunched.setEnabled(true) }
            assertIs<McpStatus.Running>(relaunched.status.value)
            runBlocking { relaunched.close() }
            runBlocking { relaunched.setEnabled(true) }
            assertEquals(McpStatus.Off, relaunched.status.value, "closed stays off")
        }

    @Test
    fun switchTurnedOffDuringStartsReadStaysOff() =
        withStorage { storage ->
            val reading = CompletableDeferred<Unit>()
            val stored = CompletableDeferred<String?>()
            val mcp =
                McpController(storage, runBlocking { McpAccess.open(storage) }, 0) {
                    reading.complete(Unit)
                    stored.await()
                }

            runBlocking {
                val start = launch { mcp.start() }
                reading.await()
                mcp.setEnabled(false)
                stored.complete(null)
                start.join()
                storage.flush()
            }

            assertEquals(false, mcp.enabled.value)
            assertEquals(McpStatus.Off, mcp.status.value)
            assertEquals("false", runBlocking { storage.setting(McpController.KEY_ENABLED) })
            runBlocking { mcp.close() }
        }

    @Test
    fun clearAllRegeneratesTheTokenAndKeepsTheSwitch() =
        withStorage { storage ->
            val access = runBlocking { McpAccess.open(storage) }
            val mcp = McpController(storage, access, 0)
            runBlocking { mcp.setEnabled(false) }
            val old = access.token.value

            runBlocking {
                storage.clearAll()
                mcp.afterClearAll()
                storage.flush()
            }

            assertNotEquals(old, access.token.value)
            assertEquals(access.token.value, runBlocking { storage.setting(McpAccess.KEY_TOKEN) })
            assertEquals("false", runBlocking { storage.setting(McpController.KEY_ENABLED) })
        }

    @Test
    fun regeneratedTokenRefusesTheOldOne() =
        withStorage { storage ->
            val access = runBlocking { McpAccess.open(storage) }
            val mcp = McpController(storage, access, 0)
            runBlocking { mcp.start() }
            val port = assertIs<McpStatus.Running>(mcp.status.value).port
            val old = access.token.value

            runBlocking { mcp.regenerate() }

            assertEquals(401, initialize(port, old))
            assertEquals(200, initialize(port, access.token.value))
            runBlocking { mcp.close() }
        }

    @Test
    fun theCommandCarriesTheAddressAndToken() {
        val command = claudeMcpAdd(47475, "abc")

        assertEquals(
            "claude mcp add --transport http app-monitor http://127.0.0.1:47475/mcp --header \"Authorization: Bearer abc\"",
            command,
        )
        assertTrue(command.contains(McpServer.PATH))
    }

    private fun initialize(
        port: Int,
        token: String,
    ): Int {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port${McpServer.PATH}"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
                .build()
        return HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }
    }
}
