package appmonitor.desktop.feature.insights

import appmonitor.desktop.domain.BenchmarkRepository
import appmonitor.desktop.domain.Durations
import appmonitor.desktop.domain.Endpoint
import appmonitor.desktop.domain.EndpointDiff
import appmonitor.desktop.domain.EndpointGroups
import appmonitor.desktop.domain.EndpointStats
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HarRepository
import appmonitor.desktop.domain.HarWrite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {
    private val benchmarks = FakeBenchmarks()
    private val hars = FakeHars()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `benchmarks are read for the filter, dropped when a newer read starts and cleared once closed`() =
        runTest {
            val viewModel = InsightsViewModel(benchmarks, hars)
            val slow = CompletableDeferred<EndpointGroups>()
            benchmarks.next = slow
            viewModel.readBenchmarks(ExchangeFilter(runId = 1))
            assertNull(viewModel.state.value.benchmarks, "null while read")

            benchmarks.next = CompletableDeferred(groupsOf("/new"))
            viewModel.readBenchmarks(ExchangeFilter(runId = 2))
            slow.complete(groupsOf("/old"))
            assertEquals(listOf(ExchangeFilter(runId = 1), ExchangeFilter(runId = 2)), benchmarks.filters)
            assertEquals(
                "/new",
                viewModel.state.value.benchmarks!!
                    .endpoints
                    .single()
                    .endpoint.path,
                "the older read dropped",
            )

            viewModel.closeBenchmarks()
            assertNull(viewModel.state.value.benchmarks)
        }

    @Test
    fun `a diff is read for both runs under the filter`() =
        runTest {
            val viewModel = InsightsViewModel(benchmarks, hars)
            viewModel.readDiff(ExchangeFilter(text = "users"), baseRunId = 1, runId = 2)
            assertEquals(Triple(ExchangeFilter(text = "users"), 1L, 2L), benchmarks.diffs.single())
            assertEquals(
                3,
                viewModel.state.value.diff!!
                    .unchanged,
            )

            viewModel.closeDiff()
            assertNull(viewModel.state.value.diff)
        }

    @Test
    fun `the export note says what the HAR write did`() =
        runTest {
            val viewModel = InsightsViewModel(benchmarks, hars)
            val file = File("run.har")
            val write = CompletableDeferred<HarWrite>()
            hars.next = write
            viewModel.exportHar(ExchangeFilter(runId = 4), file)
            assertEquals("Exporting…", viewModel.state.value.exportNote)
            write.complete(HarWrite.Written(entries = 2, more = false, maxEntries = 10_000, connections = 0))
            assertEquals("Exported 2 exchanges to run.har", viewModel.state.value.exportNote)
            assertEquals(ExchangeFilter(runId = 4) to file, hars.writes.single())

            hars.next = CompletableDeferred(HarWrite.Written(entries = 10_000, more = true, maxEntries = 10_000, connections = 3))
            viewModel.exportHar(ExchangeFilter(), file)
            assertEquals(
                "Exported 10000 exchanges (the first 10000; more matched) to run.har; 3 connections left out",
                viewModel.state.value.exportNote,
            )

            hars.next = CompletableDeferred(HarWrite.NotWritten("disk full"))
            viewModel.exportHar(ExchangeFilter(), file)
            assertEquals("Not exported: disk full", viewModel.state.value.exportNote)
        }
}

private fun groupsOf(path: String) =
    EndpointGroups(
        listOf(EndpointStats(Endpoint("GET", "api.test", path), 1, 0, 0, 0, 0, listOf(0, 1, 0, 0, 0), Durations(5, 5, 5, 5, 5))),
        exchangesRead = 1,
        truncated = false,
    )

private class FakeBenchmarks : BenchmarkRepository {
    var next = CompletableDeferred(groupsOf("/"))
    val filters = mutableListOf<ExchangeFilter>()
    val diffs = mutableListOf<Triple<ExchangeFilter, Long, Long>>()

    override suspend fun endpoints(
        filter: ExchangeFilter,
        maxExchanges: Long,
    ): EndpointGroups {
        filters += filter
        return next.await()
    }

    override suspend fun diff(
        filter: ExchangeFilter,
        baseRunId: Long,
        runId: Long,
        maxExchanges: Long,
    ): EndpointDiff {
        diffs += Triple(filter, baseRunId, runId)
        return EndpointDiff(emptyList(), emptyList(), emptyList(), unchanged = 3, truncated = false)
    }
}

private class FakeHars : HarRepository {
    var next = CompletableDeferred<HarWrite>(HarWrite.NotWritten("unset"))
    val writes = mutableListOf<Pair<ExchangeFilter, File>>()

    override suspend fun write(
        filter: ExchangeFilter,
        file: File,
    ): HarWrite {
        writes += filter to file
        return next.await()
    }
}
