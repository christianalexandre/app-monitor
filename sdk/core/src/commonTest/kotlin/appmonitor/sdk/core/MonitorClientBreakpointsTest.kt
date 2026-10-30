package appmonitor.sdk.core

import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointAbort
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointResume
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointRules
import appmonitor.v1.BreakpointStage
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.Platform
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Breakpoints as the device keeps them and waits on them (ADR-0111 A.1, B.1, ADR-0118). */
@OptIn(InternalAppMonitorApi::class)
class MonitorClientBreakpointsTest {
    private val hello =
        Hello(
            protocol_version = Protocol.VERSION,
            platform = Platform.PLATFORM_JVM,
            device_id = "device-1",
            channels = listOf(Protocol.CHANNEL_CORE, Protocol.CHANNEL_NETWORK, Protocol.CHANNEL_INTERACTION),
        )
    private val config = ClientConfig(backoffInitial = 50.milliseconds, backoffMax = 200.milliseconds)
    private val cleanup = mutableListOf<suspend () -> Unit>()
    private val request = BreakpointStage.BREAKPOINT_STAGE_REQUEST
    private val response = BreakpointStage.BREAKPOINT_STAGE_RESPONSE

    @AfterTest
    fun tearDown() =
        runBlocking {
            cleanup.asReversed().forEach { it() }
        }

    @Test
    fun theFirstRuleHoldingTheStageMatches() =
        runBlocking {
            val desktop = Desktop()
            val client = client(desktop)
            desktop.down.send(rules(rule(1, "/a", request = false), rule(2, "/*", request = true)))
            client.awaitBreakpoint("/a", 2)

            assertEquals(1L, client.breakpoints.match("GET", "https://api.example.com/a", response)?.id)
            assertNull(client.breakpoints.match("GET", "https://other.example.com/a", request))
        }

    @Test
    fun aHitWaitsForItsResumeAndOnlyThatOne() =
        runBlocking {
            val desktop = Desktop()
            val client = client(desktop)
            desktop.down.send(rules(rule(1, "/a")))
            client.awaitBreakpoint("/a", 1)

            val outcome = async { client.hold(hit("req-1", request)) }
            val sent = withTimeout(5.seconds) { desktop.hits.receive() }
            assertEquals("req-1", sent.request?.request_id)
            desktop.down.send(envelope("BreakpointResume", BreakpointResume(request_id = "req-1", stage = response, url = "x")))
            desktop.down.send(envelope("BreakpointResume", BreakpointResume(request_id = "req-2", stage = request, url = "x")))
            desktop.down.send(envelope("BreakpointResume", BreakpointResume(request_id = "req-1", stage = request, url = "https://b")))

            val resumed = assertIs<BreakpointOutcome.Resume>(withTimeout(5.seconds) { outcome.await() })
            assertEquals("https://b", resumed.changes?.url)
        }

    @Test
    fun anAbortFailsTheHeldCall() =
        runBlocking {
            val desktop = Desktop()
            val client = client(desktop)
            desktop.down.send(rules(rule(1, "/a")))
            client.awaitBreakpoint("/a", 1)

            val outcome = async { client.hold(hit("req-1", request)) }
            withTimeout(5.seconds) { desktop.hits.receive() }
            desktop.down.send(envelope("BreakpointAbort", BreakpointAbort(request_id = "req-1", stage = request, message = "stop")))

            assertEquals("stop", assertIs<BreakpointOutcome.Abort>(withTimeout(5.seconds) { outcome.await() }).message)
        }

    @Test
    fun aHitNobodyAnswersGoesOnUnchangedAtItsTimeout() =
        runBlocking {
            val desktop = Desktop()
            val client = client(desktop)
            desktop.down.send(rules(rule(1, "/a")))
            client.awaitBreakpoint("/a", 1)
            val start = TimeSource.Monotonic.markNow()

            val outcome = client.hold(hit("req-1", request, timeoutMs = 300))

            assertSame(BreakpointOutcome.UNCHANGED, outcome)
            assertTrue(start.elapsedNow() >= 300.milliseconds)
        }

    @Test
    fun aHitLargerThanAFrameGoesOnUnchangedAtOnce() =
        runBlocking {
            val desktop = Desktop()
            val client = client(desktop)
            desktop.down.send(rules(rule(1, "/a")))
            client.awaitBreakpoint("/a", 1)
            val body = ByteArray(Protocol.MAX_BODY_BYTES.toInt()).toByteString()
            val hit =
                hit("req-1", response).copy(
                    request = RequestStarted(request_id = "req-1", method = "GET", body = body),
                    response = ResponseReceived(request_id = "req-1", status_code = 200, body = body),
                )

            assertSame(BreakpointOutcome.UNCHANGED, withTimeout(5.seconds) { client.hold(hit) })
        }

    @Test
    fun aHitDroppedBeforeItIsWrittenGoesOnUnchangedAtOnce() =
        runBlocking {
            val breakpoints = Breakpoints().apply { replace(listOf(rule(1, "/a"))) }

            val outcome = withTimeout(5.seconds) { breakpoints.hold(hit("req-1", request)) { _, dropped -> dropped() } }

            assertSame(BreakpointOutcome.UNCHANGED, outcome)
        }

    @Test
    fun theSessionsEndLetsEveryHeldCallGoOnAndClearsTheRules() =
        runBlocking {
            val desktop = Desktop()
            val client = client(desktop)
            desktop.down.send(rules(rule(1, "/a")))
            client.awaitBreakpoint("/a", 1)

            val outcome = async { client.hold(hit("req-1", request, timeoutMs = 60_000)) }
            withTimeout(5.seconds) { desktop.hits.receive() }
            desktop.stop()

            assertSame(BreakpointOutcome.UNCHANGED, withTimeout(5.seconds) { outcome.await() })
            client.awaitState(ClientState.BackingOff)
            assertNull(client.breakpoints.match("GET", "https://api.example.com/a", request))
            assertSame(BreakpointOutcome.UNCHANGED, withTimeout(1.seconds) { client.hold(hit("req-2", request, timeoutMs = 60_000)) })
        }

    @Test
    fun aResumeReplacesAndRemovesHeadersByName() {
        val resumed =
            BreakpointOutcome.Resume(
                BreakpointResume(
                    request_id = "r",
                    set_headers = listOf(Header("accept", "text/plain"), Header("Accept", "text/html"), Header("X-New", "1")),
                    removed_headers = listOf("x-old"),
                ),
            )

        val headers = resumed.headers(listOf("Accept" to "*/*", "Authorization" to "real", "X-Old" to "a", "Accept" to "b"))

        assertEquals(listOf("Authorization" to "real", "accept" to "text/plain", "Accept" to "text/html", "X-New" to "1"), headers)
        assertTrue(resumed.changesHeaders)
        assertEquals(listOf("A" to "1"), BreakpointOutcome.Resume(null).headers(listOf("A" to "1")))
    }

    private suspend fun MonitorClient.awaitBreakpoint(
        path: String,
        id: Long,
    ) {
        withTimeout(5.seconds) {
            while (breakpoints.match("GET", "https://api.example.com$path", request)?.id != id) delay(10)
        }
    }

    private fun rule(
        id: Long,
        path: String,
        request: Boolean = true,
    ) = BreakpointRule(id = id, host = "api.example.com", path = path, request = request, response = true, timeout_ms = 60_000)

    private fun rules(vararg rules: BreakpointRule) = envelope("BreakpointRules", BreakpointRules(rules.toList()))

    private fun hit(
        id: String,
        stage: BreakpointStage,
        timeoutMs: Long = 60_000,
    ) = BreakpointHit(rule_id = 1, stage = stage, request = RequestStarted(request_id = id, method = "GET"), timeout_ms = timeoutMs)

    private fun envelope(
        type: String,
        message: com.squareup.wire.Message<*, *>,
    ) = Envelope(channel = Protocol.CHANNEL_INTERACTION, type = type, payload = message.encodeByteString())

    /** Sends each envelope of [down] to the device, and puts each `BreakpointHit` it sends in [hits]. */
    private inner class Desktop {
        val down = Channel<Envelope>(Channel.UNLIMITED)
        val hits = Channel<BreakpointHit>(Channel.UNLIMITED)
        lateinit var standIn: StandInDesktop

        suspend fun start(): Int {
            standIn =
                StandInDesktop.start {
                    handshake()
                    val reader =
                        launch {
                            for (frame in incoming) {
                                val envelope = Envelope.ADAPTER.decode((frame as? Frame.Binary ?: continue).readBytes())
                                if (envelope.type == "BreakpointHit") hits.send(BreakpointHit.ADAPTER.decode(envelope.payload))
                            }
                        }
                    for (envelope in down) send(Frame.Binary(true, envelope.encode()))
                    reader.join()
                }
            cleanup += { runCatching { standIn.stop() } }
            return standIn.port
        }

        suspend fun stop() = standIn.stop()
    }

    private suspend fun client(desktop: Desktop): MonitorClient {
        val port = desktop.start()
        val client = MonitorClient({ hello }, { Endpoint(LOOPBACK, port) }, config, {})
        cleanup += { client.stopAndJoin() }
        client.start()
        return client
    }
}
