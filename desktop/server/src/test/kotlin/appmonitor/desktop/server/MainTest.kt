package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.MockFailure
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.ReplayRefused
import appmonitor.v1.RequestStarted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.encodeUtf8
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `no arguments use the default port`() {
        assertEquals(Protocol.DEFAULT_PORT, parse().first)
    }

    @Test
    fun `a valid port is used`() {
        assertEquals(8080, parse("--port", "8080").first)
    }

    @Test
    fun `the proxy directory is read with the port in any order`() {
        assertEquals(ServerArgs(0, Path.of("ca")), parseOptions("--proxy", "ca", "--port", "0").first)
        assertNull(parseOptions("--port", "0").first?.proxyDirectory)
    }

    @Test
    fun `the mocks file is read with the other options`() {
        assertEquals(ServerArgs(0, mocksFile = Path.of("rules.json")), parseOptions("--mocks", "rules.json", "--port", "0").first)
        assertNull(parseOptions("--mocks").first)
    }

    @Test
    fun `a rules file becomes one mock rules set in file order`() {
        val rules =
            readMockRules(
                """
                {"rules": [
                  {"id": 3, "method": "GET", "host": "10.0.2.2", "path": "/status/418",
                   "response": {"status_code": 200, "headers": [{"name": "Content-Type", "value": "text/plain"}], "body": "mocked"}},
                  {"id": 4, "host": "10.0.2.2", "path": "/v1/*", "delay_ms": 50, "failure": {"message": "reset"}}
                ]}
                """.trimIndent(),
            ).rules
        assertEquals(
            listOf(
                MockRule(
                    id = 3,
                    method = "GET",
                    host = "10.0.2.2",
                    path = "/status/418",
                    response = MockResponse(200, listOf(Header("Content-Type", "text/plain")), "mocked".encodeUtf8()),
                ),
                MockRule(id = 4, host = "10.0.2.2", path = "/v1/*", delay_ms = 50, failure = MockFailure("reset")),
            ),
            rules,
        )
    }

    @Test
    fun `a bad rules file is refused`() {
        listOf(
            "not json",
            "{}",
            """{"rules": [{"id": 1, "host": "h", "path": "/"}]}""",
            """{"rules": [{"id": 1, "host": "h", "path": "/", "failure": {}, "response": {"status_code": 200}}]}""",
            """{"rules": [{"id": 1, "path": "/", "failure": {}}]}""",
            """{"rules": [{"id": "x", "host": "h", "path": "/", "failure": {}}]}""",
            """{"rules": [{"id": 1, "host": "h", "path": "/", "response": {"status_code": 200, "headers": [{}]}}]}""",
        ).forEach { text -> assertFailsWith<IllegalArgumentException>(text) { readMockRules(text) } }
    }

    @Test
    fun `a mocked request is logged with its rule`() {
        val started = RequestStarted(request_id = "r1", method = "GET", url = "http://h/x", mock_rule_id = 3)
        val envelope = Envelope(channel = Protocol.CHANNEL_NETWORK, type = "RequestStarted", payload = started.encodeByteString())
        assertEquals("network/RequestStarted r1 GET http://h/x mock 3", describe(envelope))
        assertEquals(
            "network/RequestStarted r1 GET http://h/x",
            describe(envelope.copy(payload = started.copy(mock_rule_id = null).encodeByteString())),
        )
    }

    @Test
    fun `the replay path is read with the other options`() {
        assertEquals(ServerArgs(0, replayPath = "/json"), parseOptions("--port", "0", "--replay", "/json").first)
        assertNull(parseOptions("--replay", "json").first)
    }

    @Test
    fun `the first request of an app to the replay path is asked again, not its replay`() {
        val started = RequestStarted(request_id = "r1", method = "GET", url = "http://h:8080/json?x=1")
        val envelope = Envelope(channel = Protocol.CHANNEL_NETWORK, type = "RequestStarted", payload = started.encodeByteString())
        assertEquals("r1", replayTarget(envelope, "/json"))
        assertNull(replayTarget(envelope, "/post"))
        val replay = envelope.copy(payload = started.copy(request_id = "r2", replay_of = "r1").encodeByteString())
        assertNull(replayTarget(replay, "/json"))
        assertEquals("network/RequestStarted r2 GET http://h:8080/json?x=1 replay_of r1", describe(replay))
        assertEquals("interaction/ReplayRequest", replayRequestEnvelope("r1", 0).run { "$channel/$type" })
    }

    // The end to end scripts match this line (ADR-0166).
    @Test
    fun `drops are logged with each count, lost unknown when the previous session is not known`() {
        assertEquals("drops: evicted 0, oversized 0, lost 0", describe(Drops(0, 0, 0)))
        assertEquals("drops: evicted 3, oversized 1, lost unknown", describe(Drops(3, 1, null)))
    }

    @Test
    fun `a refused replay is logged with its reason`() {
        val refused = ReplayRefused(request_id = "r1", reason = "not kept")
        val envelope = Envelope(channel = Protocol.CHANNEL_INTERACTION, type = "ReplayRefused", payload = refused.encodeByteString())
        assertEquals("interaction/ReplayRefused r1 not kept", describe(envelope))
    }

    @Test
    fun `a bad port is a one-line error`() {
        listOf(
            arrayOf("--port", "abc"),
            arrayOf("--port"),
            arrayOf("--port", "70000"),
            arrayOf("--prot", "1"),
            arrayOf("--port", "1", "--proxy"),
            arrayOf("--port", "abc", "--proxy", "ca"),
        ).forEach { args ->
            val (port, err) = parse(*args)

            assertNull(port, args.joinToString(" "))
            assertEquals(1, err.trimEnd().lines().size, err)
            assertTrue(err.startsWith("Usage: server"), err)
        }
    }

    @Test
    fun `the shutdown hook stops a server whose start is still running`() =
        runBlocking<Unit> {
            val bound = CompletableDeferred<Int>()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val server =
                AppMonitorServer(
                    ServerConfig(host = "127.0.0.1", port = 0, advertise = false),
                    startEngine = {
                        entered.complete(Unit)
                        gate.await()
                        it.startSuspend(wait = false)
                        bound.complete(
                            it.engine
                                .resolvedConnectors()
                                .first()
                                .port,
                        )
                    },
                ) { _, _ -> error("not advertising") }
            val hooks = CopyOnWriteArrayList<Thread>()
            val starting = async(Dispatchers.Default) { runCatching { startWithShutdownHook(server, hooks::add) } }
            withTimeout(TIMEOUT_MS) { entered.await() }

            val hook = hooks.single().apply { start() }
            gate.complete(Unit)
            hook.join(TIMEOUT_MS)

            // The hook's stop may land before or after start returns; either way the port is freed.
            assertFalse(hook.isAlive)
            withTimeout(TIMEOUT_MS) { starting.await() }
            ServerSocket(bound.await(), 0, InetAddress.getByName("127.0.0.1")).close()
        }

    private fun parse(vararg args: String): Pair<Int?, String> = parseOptions(*args).let { (options, err) -> options?.port to err }

    private fun parseOptions(vararg args: String): Pair<ServerArgs?, String> {
        val err = ByteArrayOutputStream()
        val options = parseArgs(arrayOf(*args), PrintStream(err))
        return options to err.toString()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
