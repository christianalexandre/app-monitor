package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.ExportRepository
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.ReplayKey
import appmonitor.desktop.domain.ReplayRepository
import appmonitor.desktop.domain.ReplaySent
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.domain.TrafficRepository
import appmonitor.v1.CaptureLayer
import appmonitor.v1.Hello
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModelTest {
    private val runs = FakeRuns()
    private val traffic = FakeTraffic()
    private val replays = FakeReplays()
    private val exports =
        object : ExportRepository {
            override suspend fun curl(id: Long) = if (id == 1L) Result.success("curl one") else Result.failure(Exception("no request"))
        }

    @BeforeTest
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    private fun viewModel(replays: ReplayRepository? = this.replays) = TrafficViewModel(runs, traffic, exports, replays)

    @Test
    fun `opens on every live device's requests in one list`() =
        runTest {
            traffic.sessions.value = listOf(session(10), session(11), session(20, run = 2))
            traffic.exchanges.value = listOf(exchange(1, session = 10), exchange(2, session = 11), exchange(3, session = 20))
            val viewModel = viewModel()
            val state = viewModel.state.value
            assertEquals(listOf(10L, 11L), state.sessions.map { it.id })
            assertNull(state.session)
            assertEquals(listOf(1L, 2L), state.rows.map { it.id })
            assertEquals(LIVE, state.runs.title)
            viewModel.selectSession(11)
            assertEquals(
                listOf(2L),
                viewModel.state.value.rows
                    .map { it.id },
            )
        }

    @Test
    fun `follows the picked run, session and request`() =
        runTest {
            traffic.sessions.value = listOf(session(10), session(20, run = 2), session(21, run = 2))
            traffic.exchanges.value = listOf(exchange(1, session = 10), exchange(2, session = 20), exchange(3, session = 21))
            val viewModel = viewModel()
            viewModel.showRun(2)
            assertEquals(
                listOf(2L, 3L),
                viewModel.state.value.rows
                    .map { it.id },
            )
            viewModel.selectSession(20)
            viewModel.selectRequest(2)
            val state = viewModel.state.value
            assertEquals(ShownRun.Stored(2), state.runs.shown)
            assertEquals("Run two", state.runs.title)
            assertEquals(listOf(2L), state.rows.map { it.id })
            assertEquals(2L, state.row?.id)
            assertEquals(2L, state.detail.exchange?.id)
            viewModel.showRun(null)
            assertEquals(
                listOf(10L),
                viewModel.state.value.sessions
                    .map { it.id },
            )
            assertEquals(LIVE, viewModel.state.value.runs.title)
        }

    @Test
    fun `a filter narrows the rows and show original clears it`() =
        runTest {
            traffic.sessions.value = listOf(session(10))
            traffic.exchanges.value = listOf(exchange(1, session = 10, method = "GET"), exchange(2, session = 10, method = "POST"))
            val viewModel = viewModel()
            viewModel.setFilter(ExchangeFilter(method = "POST"))
            assertEquals(
                listOf(2L),
                viewModel.state.value.rows
                    .map { it.id },
            )
            viewModel.showOriginal(1)
            assertEquals(ExchangeFilter(), viewModel.state.value.filter)
            assertEquals(
                1L,
                viewModel.state.value.row
                    ?.id,
            )
        }

    @Test
    fun `show original reads an original past the loaded pages`() =
        runTest {
            val last = TrafficRepository.PAGE_SIZE + 5
            traffic.sessions.value = listOf(session(10))
            traffic.exchanges.value = (1L..last).map { exchange(it, session = 10) }
            val viewModel = viewModel()
            viewModel.selectRequest(1)
            viewModel.showOriginal(last)
            val state = viewModel.state.value
            assertNull(state.row)
            assertEquals(last, state.detail.id)
            assertEquals("req-$last", state.detail.exchange?.requestId)
            viewModel.sendAgain()
            assertEquals(listOf("server-10" to "req-$last"), replays.sent)
            viewModel.selectRequest(1)
            assertEquals(
                1L,
                viewModel.state.value.detail.exchange
                    ?.id,
            )
        }

    @Test
    fun `loads one more page only once the last one is full`() =
        runTest {
            traffic.sessions.value = listOf(session(10))
            traffic.exchanges.value = (1L..TrafficRepository.PAGE_SIZE + 5).map { exchange(it, session = 10) }
            val viewModel = viewModel()
            assertEquals(TrafficRepository.PAGE_SIZE.toInt(), viewModel.state.value.rows.size)
            viewModel.loadMore()
            assertEquals(TrafficRepository.PAGE_SIZE.toInt() + 5, viewModel.state.value.rows.size)
            viewModel.loadMore()
            assertEquals(listOf(TrafficRepository.PAGE_SIZE, TrafficRepository.PAGE_SIZE * 2), traffic.limits.distinct())
        }

    @Test
    fun `an original no longer listed or stored leaves no request picked`() =
        runTest {
            traffic.sessions.value = listOf(session(10))
            traffic.exchanges.value = listOf(exchange(1, session = 10))
            val viewModel = viewModel()
            viewModel.selectRequest(1)
            viewModel.showOriginal(99)
            assertNull(viewModel.state.value.detail.id)
            assertNull(viewModel.state.value.detail.exchange)
        }

    @Test
    fun `saves the marked requests, or the picked one`() =
        runTest {
            traffic.sessions.value = listOf(session(10))
            traffic.exchanges.value = listOf(exchange(1, session = 10), exchange(2, session = 10))
            val viewModel = viewModel()
            viewModel.selectRequest(1)
            assertEquals(setOf(1L), viewModel.toSave().keys)
            viewModel.mark(viewModel.state.value.rows[1], true)
            assertEquals(setOf(2L), viewModel.toSave().keys)
            viewModel.reset()
            assertEquals(emptyMap(), viewModel.state.value.marked)
            assertNull(viewModel.state.value.row)
        }

    @Test
    fun `copies curl and sends again with a note for the picked request only`() =
        runTest {
            traffic.sessions.value = listOf(session(10), session(11))
            traffic.exchanges.value = listOf(exchange(1, session = 10), exchange(2, session = 10))
            val viewModel = viewModel()
            viewModel.selectRequest(1)
            var copied: String? = null
            viewModel.copyCurl { copied = it }
            viewModel.sendAgain()
            assertEquals("curl one", copied)
            assertEquals("Copied", viewModel.state.value.detail.copyNote)
            assertEquals("Sent to the app; its call shows as a replay", viewModel.state.value.detail.replayNote)
            assertEquals(listOf("server-10" to "req-1"), replays.sent)
            replays.refused.value = mapOf(ReplayKey("server-10", "req-1") to "not idempotent")
            assertEquals("The app did not send it again: not idempotent", viewModel.state.value.detail.replayNote)
            viewModel.selectRequest(2)
            assertNull(viewModel.state.value.detail.copyNote)
            assertNull(viewModel.state.value.detail.replayNote)
            viewModel.copyCurl { copied = it }
            assertEquals("no request", viewModel.state.value.detail.copyNote)
        }

    @Test
    fun `offers no replay without a replay repository`() =
        runTest {
            assertEquals(
                false,
                viewModel(replays = null)
                    .state.value.detail.canReplay,
            )
        }

    @Test
    fun `Clear empties the live list and drops its pick and marks`() =
        runTest {
            traffic.sessions.value = listOf(session(10), session(20, run = 2))
            traffic.exchanges.value = listOf(exchange(1, session = 10), exchange(2, session = 10), exchange(3, session = 20))
            val viewModel = viewModel()
            viewModel.showRun(2)
            viewModel.mark(viewModel.state.value.rows[0], true)
            viewModel.showRun(null)
            viewModel.selectRequest(1)
            viewModel.mark(viewModel.state.value.rows[1], true)
            viewModel.clear()
            val state = viewModel.state.value
            assertEquals(emptyList(), state.rows)
            assertNull(state.row)
            assertEquals(setOf(3L), state.marked.keys)
        }

    @Test
    fun `Save stores the marked live requests and unmarks them, Saved apart from the recordings`() =
        runTest {
            traffic.sessions.value = listOf(session(10), session(20, run = 2))
            traffic.exchanges.value = listOf(exchange(1, session = 10), exchange(2, session = 10), exchange(3, session = 20))
            val viewModel = viewModel()
            viewModel.showRun(2)
            viewModel.mark(viewModel.state.value.rows[0], true)
            viewModel.showRun(null)
            viewModel.mark(viewModel.state.value.rows[0], true)
            viewModel.mark(viewModel.state.value.rows[1], true)
            viewModel.save()
            val state = viewModel.state.value
            assertEquals(setOf(3L), state.marked.keys, "a stored one is not saved again")
            assertEquals("Saved 2 of 3 to Saved", state.runs.saveNote)

            runs.save()
            assertEquals(
                5L,
                viewModel.state.value.runs.saved
                    ?.id,
            )
            assertEquals(
                listOf(2L, 1L),
                viewModel.state.value.runs.recordings
                    .map { it.id },
            )
            viewModel.clear()
            assertNull(viewModel.state.value.runs.saveNote)
        }

    @Test
    fun `records into a new run and stops while the live list stays shown`() =
        runTest {
            val viewModel = viewModel()
            viewModel.record()
            val recording = viewModel.state.value.runs
            assertEquals(3L, recording.recording)
            assertEquals(listOf(3L, 2L, 1L), recording.runs.map { it.id })
            assertEquals(ShownRun.Live, recording.shown)
            viewModel.stop()
            val stopped = viewModel.state.value.runs
            assertNull(stopped.recording)
            assertEquals(4_000L, stopped.runs.first().endedAtMs)
        }

    private class FakeRuns : RunRepository {
        private val runs = MutableStateFlow(listOf(Run(2, "Run two", 2_000, null), Run(1, "Run one", 1_000, 1_500)))
        override val recording = MutableStateFlow<Long?>(null)

        override fun observeRuns(): Flow<List<Run>> = runs

        override suspend fun record(): Long {
            val id = runs.value.maxOf { it.id } + 1
            runs.value = listOf(Run(id, null, 3_000, null)) + runs.value
            recording.value = id
            return id
        }

        fun save() {
            runs.value = listOf(Run(5, "Saved", 5_000, 5_000, saved = true)) + runs.value
        }

        override suspend fun stop() {
            runs.value = runs.value.map { if (it.id == recording.value) it.copy(endedAtMs = 4_000) else it }
            recording.value = null
        }

        override suspend fun rename(
            id: Long,
            name: String,
        ) {
            runs.value = runs.value.map { if (it.id == id) it.copy(name = name.ifBlank { null }) else it }
        }
    }

    private class FakeTraffic : TrafficRepository {
        val sessions = MutableStateFlow(emptyList<Session>())
        val exchanges = MutableStateFlow(emptyList<Exchange>())
        val limits = mutableListOf<Long>()
        override val health: Flow<StoreHealth> = MutableStateFlow(StoreHealth())

        // Run 0 stands for the live list, as the routed repository reads it.
        override fun observeSessions(runId: Long?): Flow<List<Session>> = sessions.map { list -> list.filter { it.runId == (runId ?: 0) } }

        override fun observeExchanges(
            filter: ExchangeFilter,
            limit: Long,
        ): Flow<List<Exchange>> {
            limits += limit
            return combine(sessions, exchanges) { sessions, list ->
                val shown = filter.sessionId?.let(::setOf) ?: sessions.filter { it.runId == (filter.runId ?: 0) }.map { it.id }.toSet()
                list.filter { it.sessionId in shown && (filter.method == null || it.method == filter.method) }.take(limit.toInt())
            }
        }

        override suspend fun exchange(
            id: Long,
            frameLimit: Long,
        ): HttpExchange? = exchanges.value.firstOrNull { it.id == id }?.let { HttpExchange(it.id, it.requestId, it.method, it.url) }

        override suspend fun save(exchangeIds: Collection<Long>): Map<Long, Long> {
            val live =
                sessions.value
                    .filter { it.runId == 0L }
                    .map { it.id }
                    .toSet()
            return exchanges.value.filter { it.id in exchangeIds && it.sessionId in live }.associate { it.id to it.id + 1_000 }
        }

        override fun clear() {
            val live =
                sessions.value
                    .filter { it.runId == 0L }
                    .map { it.id }
                    .toSet()
            exchanges.value = exchanges.value.filter { it.sessionId !in live }
        }
    }

    private class FakeReplays : ReplayRepository {
        val sent = mutableListOf<Pair<String, String>>()
        override val refused = MutableStateFlow(emptyMap<ReplayKey, String>())

        override fun send(
            sessionId: String,
            requestId: String,
        ): ReplaySent {
            sent += sessionId to requestId
            return ReplaySent.SENT
        }
    }
}

private fun session(
    id: Long,
    run: Long = 0,
) = Session(id, run, "server-$id", Hello(device_name = "Device $id", app_id = "app"), null, 0, null, null)

private fun exchange(
    id: Long,
    session: Long,
    method: String = "GET",
) = Exchange(
    id,
    session,
    "req-$id",
    method,
    "https://example.com/$id",
    "example.com",
    200,
    false,
    0,
    10,
    CaptureLayer.CAPTURE_LAYER_PLATFORM,
    null,
    0,
)
