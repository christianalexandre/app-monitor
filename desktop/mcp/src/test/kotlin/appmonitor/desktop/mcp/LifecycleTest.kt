package appmonitor.desktop.mcp

import appmonitor.desktop.storage.StorageHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.BindException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class LifecycleTest {
    @Test
    fun toolCallWhileTheStoreClosesIsAnError() =
        withServer { running ->
            running.storage.close(5.seconds)
            val result = running.client { it.callTool(ListRuns.NAME, emptyMap()) }
            assertEquals(true, result.isError)
            assertEquals(CLOSING, result.text)
        }

    @Test
    fun toolsListNeedsNoStore() =
        withServer { running ->
            running.storage.close(5.seconds)
            val tools = running.client { it.listTools().tools }
            assertTrue(tools.any { it.name == ListRuns.NAME })
        }

    @Test
    fun lostWritesAreAWarning() =
        withServer(health = { StorageHealth(notSaved = 3, lastError = "disk I/O error") }) { running ->
            val result = running.client { it.callTool(ListRuns.NAME, emptyMap()) }
            assertNotEquals(true, result.isError)
            assertEquals(
                "3 events were not saved: disk I/O error",
                result.json["warnings"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            assertEquals(1, result.json["runs"]!!.jsonArray.size, "what is committed is still answered")
        }

    @Test
    fun startReturnsOnceBound() =
        withStorage { storage ->
            val port = freePort()
            val server = McpServer(storage, McpAccess.open(storage), port)
            assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }
            assertEquals(port, server.start())
            try {
                Socket("127.0.0.1", port).close()
            } finally {
                server.stop()
            }
        }

    @Test
    fun callInFlightCompletesDuringStop() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withServer(beforeTool = {
            entered.complete(Unit)
            release.await()
        }) { running ->
            coroutineScope {
                val call = async { running.client { it.callTool(ListRuns.NAME, emptyMap()) } }
                entered.await()
                val stop = async { running.server.stop() }
                delay(100)
                release.complete(Unit)
                assertNotEquals(true, withTimeout(5.seconds) { call.await() }.isError)
                withTimeout(McpServer.STOP_TIMEOUT) { stop.await() }
            }
        }
    }

    @Test
    fun noConnectionAfterStop() =
        withServer { running ->
            running.server.stop()
            assertFailsWith<ConnectException> { Socket("127.0.0.1", running.port).close() }
        }

    @Test
    fun portTakenFailsStartWithBindException() =
        withStorage { storage ->
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
                val server = McpServer(storage, McpAccess.open(storage), taken.localPort)
                assertFailsWith<BindException> { server.start() }
                server.stop()
            }
        }

    @Test
    fun stopAfterFailedStart() =
        withStorage { storage ->
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
                val server = McpServer(storage, McpAccess.open(storage), taken.localPort)
                runCatching { server.start() }
                withTimeout(1.seconds) { server.stop() }
            }
        }

    @Test
    fun bindsLoopbackOnly() =
        withServer { running ->
            val other =
                NetworkInterface
                    .networkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address }
            if (other == null) {
                println("bindsLoopbackOnly: no non-loopback IPv4 address, only the loopback half checked")
            } else {
                assertFailsWith<ConnectException>("$other") {
                    Socket().use { it.connect(InetSocketAddress(other, running.port), 2_000) }
                }
            }
            Socket("127.0.0.1", running.port).close()
        }

    @Test
    fun startTwiceFails() =
        withServer { running ->
            assertFailsWith<IllegalStateException> { running.server.start() }
        }

    @Test
    fun stopFreesThePort() =
        withServer { running ->
            val mark = TimeSource.Monotonic.markNow()
            running.server.stop()
            assertTrue(mark.elapsedNow() < McpServer.STOP_TIMEOUT, "stopped in ${mark.elapsedNow()}")
            ServerSocket(running.port, 50, InetAddress.getByName("127.0.0.1")).close()
        }

    @Test
    fun stopDuringStart() =
        withStorage { storage ->
            val port = freePort()
            val server = McpServer(storage, McpAccess.open(storage), port)
            coroutineScope {
                val start = async { runCatching { server.start() } }
                val stop = async { server.stop() }
                stop.await()
                start.await()
            }
            assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }
            assertFailsWith<IllegalStateException> { server.start() }
        }

    @Test
    fun stopTwice() =
        withServer { running ->
            running.server.stop()
            val mark = TimeSource.Monotonic.markNow()
            running.server.stop()
            assertTrue(mark.elapsedNow() < 1.seconds, "second stop took ${mark.elapsedNow()}")
        }

    private fun freePort(): Int = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
