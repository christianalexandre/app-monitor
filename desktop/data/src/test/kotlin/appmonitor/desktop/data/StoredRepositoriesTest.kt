package appmonitor.desktop.data

import appmonitor.desktop.domain.BreakpointChange
import appmonitor.desktop.domain.BreakpointWrite
import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.Durations
import appmonitor.desktop.domain.Endpoint
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.Issue
import appmonitor.desktop.domain.IssueChange
import appmonitor.desktop.domain.IssueSource
import appmonitor.desktop.domain.IssueStatus
import appmonitor.desktop.domain.IssueWrite
import appmonitor.desktop.domain.MAX_GROUPED_EXCHANGES
import appmonitor.desktop.domain.MIN_MOVE_MS
import appmonitor.desktop.domain.MIN_MOVE_PERCENT
import appmonitor.desktop.domain.MockChange
import appmonitor.desktop.domain.MockSource
import appmonitor.desktop.domain.MockWrite
import appmonitor.desktop.domain.Retention
import appmonitor.desktop.domain.StatusClass
import appmonitor.desktop.storage.MAX_BREAKPOINT_TIMEOUT_MS
import appmonitor.desktop.storage.Storage
import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointRule
import appmonitor.v1.Envelope
import appmonitor.v1.Header
import appmonitor.v1.Hello
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import appmonitor.v1.Platform
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import com.squareup.wire.Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import appmonitor.desktop.storage.MAX_GROUPED_EXCHANGES as StoredMaxGrouped
import appmonitor.desktop.storage.MIN_MOVE_MS as StoredMinMoveMs
import appmonitor.desktop.storage.MIN_MOVE_PERCENT as StoredMinMovePercent

class StoredRepositoriesTest {
    @Test
    fun `runs are read, renamed and recorded through the repository`() =
        withStorage { storage ->
            val runs = storage.runRepository()
            runs.rename(storage.runId, "  login  ")
            storage.flush()
            assertEquals(
                "login",
                runs
                    .observeRuns()
                    .first()
                    .single()
                    .name,
            )

            val next = runs.record()!!
            assertEquals(next, runs.recording.value)
            assertEquals(listOf(next, next - 1), runs.observeRuns().first().map { it.id })
            runs.stop()
            assertNull(runs.recording.value)
            assertNotNull(
                runs
                    .observeRuns()
                    .first()
                    .first()
                    .endedAtMs,
            )
        }

    @Test
    fun `sessions, filtered exchanges and one exchange read as the store holds them`() =
        withStorage { storage ->
            storage.record(
                envelope(RequestStarted("r1", "GET", "https://api.test/users", listOf(Header("Accept", "*/*")))),
                envelope(ResponseReceived("r1", 200, body = "[]".encodeUtf8(), body_size = 2)),
                envelope(RequestStarted("r2", "POST", "https://api.test/login")),
                envelope(RequestFailed("r2", "timeout")),
            )
            val traffic = storage.trafficRepository()

            val session = traffic.observeSessions(storage.runId).first().single()
            assertEquals("app", session.hello.app_id)
            assertEquals(listOf(session), traffic.observeSessions(null).first())

            val all = traffic.observeExchanges(ExchangeFilter(sessionId = session.id)).first()
            assertEquals(listOf("r1", "r2"), all.map { it.requestId })
            assertEquals(listOf(false, true), all.map { it.isError })
            val ok = traffic.observeExchanges(ExchangeFilter(sessionId = session.id, statusClasses = setOf(StatusClass.SUCCESS))).first()
            assertEquals(listOf("r1"), ok.map { it.requestId })
            assertEquals(listOf("r2"), traffic.observeExchanges(ExchangeFilter(text = "login")).first().map { it.requestId })

            val detail = traffic.exchange(all.first().id)!!
            assertEquals(listOf(Header("Accept", "*/*")), detail.requestHeaders)
            assertEquals(CapturedBody("[]".encodeUtf8(), originalSize = 2), detail.responseBody)
            assertEquals(200, detail.statusCode)
            assertNull(detail.connection)
            assertEquals("timeout", traffic.exchange(all.last().id)!!.error)
            assertNull(traffic.exchange(all.last().id + 1))
        }

    @Test
    fun `endpoints and a run diff read the store's grouping`() =
        withStorage { storage ->
            val base = storage.runId
            storage.record(
                envelope(RequestStarted("r1", "GET", "https://API.test/users?page=1")),
                envelope(ResponseReceived("r1", 200, body = "[]".encodeUtf8(), body_size = 2)),
                envelope(RequestStarted("r2", "POST", "https://api.test/login", body = "hi".encodeUtf8())),
                envelope(RequestFailed("r2", "timeout")),
            )
            val run = storage.runRepository().record()!!
            storage.connected("s2", HELLO, "127.0.0.1")
            storage.received("s2", envelope(RequestStarted("r3", "GET", "https://api.test/users")))
            storage.received("s2", envelope(ResponseReceived("r3", 500)))
            storage.flush()
            val benchmarks = storage.benchmarkRepository()

            val groups = benchmarks.endpoints(ExchangeFilter(runId = base))
            assertEquals(2, groups.exchangesRead)
            assertEquals(false, groups.truncated)
            val (users, login) = groups.endpoints.sortedBy { it.endpoint.path }.reversed()
            assertEquals(Endpoint("GET", "api.test", "/users"), users.endpoint)
            assertEquals(listOf(0L, 1L, 0L, 0L, 0L), users.statusClasses)
            assertEquals(Durations(0, 0, 0, 0, 0), users.durations)
            assertEquals(2L, users.responseBytes)
            assertEquals(1L, login.failed)
            assertEquals(1.0, login.errorRate)
            assertEquals(2L, login.requestBytes)

            val diff = benchmarks.diff(ExchangeFilter(runId = run), base, run)
            assertEquals(listOf("/login"), diff.gone.map { it.endpoint.path })
            assertEquals(emptyList(), diff.added)
            assertEquals(listOf(0.0 to 1.0), diff.changed.map { it.base.errorRate to it.run.errorRate })
            assertEquals(0, diff.unchanged)
        }

    @Test
    fun `a run is read per endpoint and device, a device reconnected in it counted once`() =
        withStorage { storage ->
            storage.record(envelope(RequestStarted("r0", "GET", "https://api.test/users")))
            val run = storage.runRepository().record()!!
            val iphone = HELLO.copy(device_id = "d2", device_name = "iPhone", platform = Platform.PLATFORM_IOS)
            storage.connected("s1", HELLO, "127.0.0.1")
            storage.received("s1", envelope(RequestStarted("r1", "GET", "https://api.test/users")))
            storage.received("s1", envelope(ResponseReceived("r1", 200, body = "[]".encodeUtf8(), body_size = 2)))
            storage.disconnected("s1")
            storage.connected("s2", iphone, "127.0.0.1")
            storage.received("s2", envelope(RequestStarted("r1", "GET", "https://api.test/users")))
            storage.received("s2", envelope(ResponseReceived("r1", 500)))
            storage.connected("s3", HELLO, "127.0.0.1")
            storage.received("s3", envelope(RequestStarted("r2", "GET", "https://api.test/users")))
            storage.received("s3", envelope(RequestStarted("r3", "GET", "https://api.test/feed")))
            storage.flush()

            val comparison = storage.benchmarkRepository().byDevice(ExchangeFilter(), run)
            assertEquals(listOf("d1" to "Pixel", "d2" to "iPhone"), comparison.devices.map { it.deviceId to it.name })
            assertEquals(4, comparison.exchangesRead, "the exchange before Record is not in the run")
            assertEquals(listOf("/users", "/feed"), comparison.endpoints.map { it.endpoint.path })
            val users = comparison.endpoints.first().byDevice
            assertEquals(2L, users.getValue("d1").count)
            assertEquals(listOf(0L, 1L, 0L, 0L, 0L), users.getValue("d1").statusClasses)
            assertEquals(2L, users.getValue("d1").responseBytes)
            assertEquals(listOf(0L, 0L, 0L, 0L, 1L), users.getValue("d2").statusClasses)
            assertEquals(
                setOf("d1"),
                comparison.endpoints
                    .last()
                    .byDevice.keys,
            )

            val filtered =
                storage.benchmarkRepository().byDevice(
                    ExchangeFilter(statusClasses = setOf(StatusClass.SERVER_ERROR), runId = 99),
                    run,
                )
            assertEquals(listOf(setOf("d2")), filtered.endpoints.map { it.byDevice.keys })
        }

    @Test
    fun `runs, a search page, replays, one exchange and its frames read through the query repository`() =
        withStorage { storage ->
            storage.record(
                envelope(RequestStarted("r1", "GET", "wss://api.test/socket")),
                envelope(
                    WebSocketFrame("r1", WebSocketFrame.Direction.DIRECTION_SENT, WebSocketFrame.Opcode.OPCODE_TEXT, "a".encodeUtf8()),
                ),
                envelope(
                    WebSocketFrame("r1", WebSocketFrame.Direction.DIRECTION_RECEIVED, WebSocketFrame.Opcode.OPCODE_TEXT, "b".encodeUtf8()),
                ),
                envelope(RequestStarted("r2", "GET", "https://api.test/socket", replay_of = "r1")),
                envelope(ResponseReceived("r2", 204)),
            )
            val queries = storage.trafficQueryRepository()

            assertEquals(listOf(storage.runId), queries.runs().map { it.id })
            val summary = queries.runSummaries(Long.MAX_VALUE, 10).single()
            assertEquals(2L, summary.exchangeCount)
            assertEquals(listOf("d1" to "Pixel"), summary.sessions.map { it.deviceId to it.deviceName })
            assertEquals(Platform.PLATFORM_ANDROID, summary.sessions.single().platform)

            val (socket, replay) = queries.search(ExchangeFilter(), 0, 10)
            assertEquals(listOf(replay), queries.search(ExchangeFilter(), socket.id, 10))
            assertEquals(listOf(socket), queries.search(ExchangeFilter(), 0, 1))
            assertEquals(mapOf(replay.id to socket.id), queries.replayOriginals(listOf(socket, replay)))

            val detail = queries.detail(replay.id)!!
            assertEquals(replay, detail.exchange)
            assertEquals("r1", detail.request?.replay_of)
            assertEquals(204, detail.response?.status_code)
            assertEquals(socket.id, detail.replayOfId)
            assertNull(detail.connectionBytes)
            assertNull(queries.detail(replay.id + 1))

            val frames = queries.frames(socket.id, 0, 10)
            assertEquals(listOf("a", "b"), frames.map { it.payload.bytes.utf8() })
            assertEquals(listOf(frames.last()), queries.frames(socket.id, frames.first().id, 10))
            assertEquals(false, queries.isClosing)
        }

    @Test
    fun `the grouping cap and the diff thresholds are the store's`() =
        withStorage { storage ->
            assertEquals(StoredMaxGrouped, MAX_GROUPED_EXCHANGES)
            assertEquals(StoredMinMoveMs, MIN_MOVE_MS)
            assertEquals(StoredMinMovePercent, MIN_MOVE_PERCENT)
            storage.record(
                envelope(RequestStarted("r1", "GET", "https://api.test/a")),
                envelope(RequestStarted("r2", "GET", "https://api.test/b")),
            )
            val groups = storage.benchmarkRepository().endpoints(ExchangeFilter(), maxExchanges = 1)
            assertEquals(1, groups.exchangesRead)
            assertEquals(true, groups.truncated)
        }

    @Test
    fun `every status class maps to the store's`() {
        StatusClass.entries.forEach { assertEquals(it.name, it.toStored().name) }
        assertEquals(StatusClass.entries.map { it.from }, StatusClass.entries.map { it.toStored().from })
    }

    @Test
    fun `health starts at the store's`() =
        withStorage { storage ->
            assertEquals(
                0,
                storage
                    .trafficRepository()
                    .health
                    .first()
                    .notSaved,
            )
        }

    @Test
    fun `mock rules are created pending from MCP, confirmed, changed and deleted through the repository`() =
        withStorage { storage ->
            val mocks = storage.mockRepository()
            val rule = MockRule(host = "api.test", path = "/users", response = MockResponse(status_code = 503))
            val id = (mocks.create("app", "  down  ", rule, MockSource.MCP) as MockWrite.Saved).id
            val created = mocks.observeMocks().first().single()
            assertEquals(listOf("down", "app"), listOf(created.name, created.appId))
            assertEquals(MockSource.MCP, created.source)
            assertEquals(id, created.rule.id)
            assertEquals(false, created.active)

            mocks.confirm(id)
            mocks.update(id, MockChange(enabled = false))
            storage.flush()
            val changed = mocks.observeMocks().first().single()
            assertEquals(listOf(false, false), listOf(changed.pending, changed.enabled))
            assertEquals(MockWrite.UnknownMock, mocks.update(id + 1, MockChange(name = "other")))

            mocks.delete(id)
            storage.flush()
            assertEquals(emptyList(), mocks.observeMocks().first())
        }

    @Test
    fun `breakpoints are created, changed and deleted through the repository`() =
        withStorage { storage ->
            val breakpoints = storage.breakpointRepository()
            val rule = BreakpointRule(host = "api.test", path = "/login", request = true, timeout_ms = 30_000)
            val id = (breakpoints.create("app", "login", rule) as BreakpointWrite.Saved).id
            breakpoints.update(id, BreakpointChange(enabled = false))
            storage.flush()
            val stored = breakpoints.observeBreakpoints().first().single()
            assertEquals(listOf("login", "app"), listOf(stored.name, stored.appId))
            assertEquals(rule.copy(id = id), stored.rule)
            assertEquals(false, stored.enabled)
            assertEquals(BreakpointWrite.UnknownBreakpoint, breakpoints.update(id + 1, BreakpointChange(name = "other")))
            assertEquals(MAX_BREAKPOINT_TIMEOUT_MS, breakpoints.maxTimeoutMs)

            breakpoints.delete(id)
            storage.flush()
            assertEquals(emptyList(), breakpoints.observeBreakpoints().first())
        }

    @Test
    fun `issues are created, listed with their exchanges, edited and deleted through the repository`() =
        withStorage { storage ->
            storage.record(
                envelope(RequestStarted(request_id = "r1", method = "POST", url = "https://api.example.com/cart")),
                envelope(ResponseReceived(request_id = "r1", status_code = 500)),
            )
            val exchange =
                storage
                    .trafficRepository()
                    .observeExchanges(ExchangeFilter())
                    .first()
                    .single()
                    .id
            val issues = storage.issueRepository()

            val saved = issues.create(" Cart fails ", "500", listOf("flaky", "checkout"), listOf(exchange))
            val id = (saved as IssueWrite.Saved).id
            assertEquals(IssueWrite.UnknownExchanges(listOf(exchange + 1)), issues.create("Other", "", emptyList(), listOf(exchange + 1)))
            val issue = issues.observeIssues().first().single()
            assertEquals(
                Issue(
                    id,
                    "Cart fails",
                    "500",
                    listOf("checkout", "flaky"),
                    IssueStatus.OPEN,
                    IssueSource.WINDOW,
                    issue.createdAtMs,
                    issue.updatedAtMs,
                    1,
                ),
                issue,
            )
            assertEquals(listOf("checkout", "flaky"), issues.observeTags().first())
            assertEquals(listOf("r1" to 500), issues.exchanges(id).map { it.requestId to it.statusCode })

            assertEquals(IssueWrite.Saved(id), issues.update(id, IssueChange(status = IssueStatus.RESOLVED, tags = listOf("checkout"))))
            assertEquals(
                IssueStatus.RESOLVED to listOf("checkout"),
                issues
                    .observeIssues()
                    .first()
                    .single()
                    .let { it.status to it.tags },
            )
            assertEquals(IssueWrite.UnknownIssue, issues.update(id + 1, IssueChange(note = "gone")))

            issues.delete(id)
            storage.flush()
            assertEquals(emptyList(), issues.observeIssues().first())
        }

    @Test
    fun `retention is read and written through the repository`() =
        withStorage { storage ->
            val retention = storage.retentionRepository()
            assertEquals(Retention(7, 1L shl 30), retention.retention.first())
            retention.setRetention(Retention(null, 2L shl 30))
            assertEquals(Retention(null, 2L shl 30), retention.retention.first())
            assertEquals(storage.retentionStatus.value.usedBytes, retention.state.first().usedBytes)
            assertNull(retention.state.first().lastError)
        }
}

internal val HELLO =
    Hello(
        protocol_version = Protocol.VERSION,
        platform = Platform.PLATFORM_ANDROID,
        device_id = "d1",
        device_name = "Pixel",
        os_version = "Android 16",
        app_id = "app",
        app_version = "1.0",
        channels = listOf(Protocol.CHANNEL_NETWORK),
    )

internal fun envelope(message: Message<*, *>) =
    Envelope(
        channel = Protocol.CHANNEL_NETWORK,
        type = message::class.simpleName!!,
        payload = message.encodeByteString(),
        timestamp_ms = 1_000,
    )

internal suspend fun Storage.record(vararg envelopes: Envelope) {
    connected("s1", HELLO, "127.0.0.1")
    envelopes.forEach { received("s1", it) }
    disconnected("s1")
    flush()
}

@OptIn(ExperimentalPathApi::class)
internal fun withStorage(block: suspend (Storage) -> Unit) {
    val dir = createTempDirectory("data-test")
    val storage = Storage.open(dir.resolve("data"))
    try {
        runBlocking { block(storage) }
    } finally {
        runBlocking { storage.close(5.seconds) }
        dir.deleteRecursively()
    }
}
