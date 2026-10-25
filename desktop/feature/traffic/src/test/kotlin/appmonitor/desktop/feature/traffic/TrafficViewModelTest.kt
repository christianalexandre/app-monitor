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
    fun `shows the current run's first session and its requests`() =
        runTest {
            traffic.sessions.value = listOf(session(10, run = 1), session(11, run = 1), session(20, run = 2))
            traffic.exchanges.value = listOf(exchange(1, session = 10), exchange(2, session = 11))
            val state = viewModel().state.value
            assertEquals(listOf(10L, 11L), state.sessions.map { it.id })
            assertEquals(10L, state.session?.id)
            assertEquals(listOf(1L), state.rows.map { it.id })
            assertEquals("Run one", state.runs.title)
        }

    @Test
    fun `follows the picked run, session and request`() =
        runTest {
            traffic.sessions.value = listOf(session(10, run = 1), session(20, run = 2))
            traffic.exchanges.value = listOf(exchange(1, session = 10), exchange(2, session = 20))
            val viewModel = viewModel()
            viewModel.showRun(2)
            viewModel.selectSession(20)
            viewModel.selectRequest(2)
            val state = viewModel.state.value
            assertEquals(ShownRun.Earlier(2), state.runs.shown)
            assertEquals(2L, state.row?.id)
            assertEquals(2L, state.detail.exchange?.id)
            viewModel.showRun(null)
            assertEquals(
                listOf(10L, 20L),
                viewModel.state.value.sessions
                    .map { it.id },
            )
            assertEquals("All runs", viewModel.state.value.runs.title)
        }

    @Test
    fun `a filter narrows the rows and show original clears it`() =
        runTest {
            traffic.sessions.value = listOf(session(10, run = 1))
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
            traffic.sessions.value = listOf(session(10, run = 1))
            traffic.exchanges.value = (1L..last).map { exchange(it, session = 10) }
            val viewModel = viewModel()
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
            traffic.sessions.value = listOf(session(10, run = 1))
            traffic.exchanges.value = (1L..TrafficRepository.PAGE_SIZE + 5).map { exchange(it, session = 10) }
            val viewModel = viewModel()
            assertEquals(TrafficRepository.PAGE_SIZE.toInt(), viewModel.state.value.rows.size)
            viewModel.loadMore()
            assertEquals(TrafficRepository.PAGE_SIZE.toInt() + 5, viewModel.state.value.rows.size)
            viewModel.loadMore()
            assertEquals(listOf(TrafficRepository.PAGE_SIZE, TrafficRepository.PAGE_SIZE * 2), traffic.limits.distinct())
        }

    @Test
    fun `saves the marked requests, or the picked one`() =
        runTest {
            traffic.sessions.value = listOf(session(10, run = 1))
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
            traffic.sessions.value = listOf(session(10, run = 1), session(11, run = 1))
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
    fun `a new run is shown at once`() =
        runTest {
            val viewModel = viewModel()
            viewModel.showRun(null)
            viewModel.newRun()
            assertEquals(ShownRun.Current, viewModel.state.value.runs.shown)
            assertEquals(3L, viewModel.state.value.runs.shownRunId)
        }

    private class FakeRuns : RunRepository {
        private val runs = MutableStateFlow(listOf(Run(2, null, 2_000, null), Run(1, "Run one", 1_000, 1_500)))
        override val currentRun = MutableStateFlow(1L)

        override fun observeRuns(): Flow<List<Run>> = runs

        override suspend fun newRun(): Long {
            val id = runs.value.maxOf { it.id } + 1
            runs.value = listOf(Run(id, null, 3_000, null)) + runs.value
            currentRun.value = id
            return id
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

        override fun observeSessions(runId: Long?): Flow<List<Session>> =
            sessions.map { list ->
                list.filter {
                    runId == null ||
                        it.runId == runId
                }
            }

        override fun observeExchanges(
            filter: ExchangeFilter,
            limit: Long,
        ): Flow<List<Exchange>> {
            limits += limit
            return exchanges.map { list ->
                list.filter { it.sessionId == filter.sessionId && (filter.method == null || it.method == filter.method) }.take(
                    limit.toInt(),
                )
            }
        }

        override suspend fun exchange(
            id: Long,
            frameLimit: Long,
        ): HttpExchange? = exchanges.value.firstOrNull { it.id == id }?.let { HttpExchange(it.id, it.requestId, it.method, it.url) }
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
    run: Long,
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
