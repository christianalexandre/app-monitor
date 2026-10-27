package appmonitor.desktop.feature.breakpoints

import appmonitor.desktop.domain.Breakpoint
import appmonitor.desktop.domain.BreakpointChange
import appmonitor.desktop.domain.BreakpointRepository
import appmonitor.desktop.domain.BreakpointWrite
import appmonitor.desktop.domain.HeldCall
import appmonitor.desktop.domain.HeldCallRepository
import appmonitor.desktop.domain.HeldEdit
import appmonitor.v1.BreakpointHit
import appmonitor.v1.BreakpointRule
import appmonitor.v1.BreakpointStage
import appmonitor.v1.RequestStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
class BreakpointsViewModelTest {
    private val repository = FakeBreakpoints()
    private val heldCalls = FakeHeldCalls()

    @BeforeTest
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `turns a breakpoint off, saves and deletes`() =
        runTest {
            repository.breakpoints.value = listOf(breakpoint(1))
            val viewModel = BreakpointsViewModel(repository, heldCalls)
            viewModel.toggle(
                viewModel.state.value.breakpoints
                    .single(),
            )
            assertEquals(
                false,
                viewModel.state.value.breakpoints
                    .single()
                    .enabled,
            )

            val rule = BreakpointRule(host = "api.test", path = "/new", response = true, timeout_ms = 1_000)
            assertNull(viewModel.save(null, "app", "new", rule))
            assertEquals("Not saved: the breakpoint was deleted", viewModel.save(breakpoint(9), "app", "gone", rule))

            viewModel.delete(1)
            assertEquals(
                listOf("new"),
                viewModel.state.value.breakpoints
                    .map { it.name },
            )
            assertEquals(60_000L, viewModel.maxTimeoutMs)
        }

    @Test
    fun `follows the held calls and resumes or aborts them`() =
        runTest {
            val viewModel = BreakpointsViewModel(repository, heldCalls)
            val first = held("r1")
            val second = held("r2")
            heldCalls.held.value = listOf(first, second)
            assertEquals(listOf(first, second), viewModel.state.value.held)

            viewModel.resume(first, first.unedited.copy(url = "https://api.test/other"))
            viewModel.abort(second, "no")
            assertEquals(listOf("resume r1 https://api.test/other", "abort r2 no"), heldCalls.answers)
            assertEquals(emptyList(), viewModel.state.value.held)
        }

    @Test
    fun `holds nothing without held calls`() =
        runTest {
            val viewModel = BreakpointsViewModel(repository, null)
            assertEquals(emptyList(), viewModel.state.value.held)
            viewModel.abort(held("r1"), "no")
        }
}

private fun breakpoint(id: Long) =
    Breakpoint(id, "app", id, "breakpoint $id", true, BreakpointRule(host = "api.test", path = "/$id", request = true, id = id), 0, 0)

private fun held(requestId: String) =
    HeldCall(
        sessionId = "s1",
        appId = "app",
        hit =
            BreakpointHit(
                stage = BreakpointStage.BREAKPOINT_STAGE_REQUEST,
                request = RequestStarted(requestId, "GET", "https://api.test/$requestId"),
                timeout_ms = 1_000,
            ),
        heldAtMs = 0,
    )

private class FakeBreakpoints : BreakpointRepository {
    val breakpoints = MutableStateFlow<List<Breakpoint>>(emptyList())

    override val maxTimeoutMs = 60_000L

    override fun observeBreakpoints(): Flow<List<Breakpoint>> = breakpoints

    override suspend fun create(
        appId: String,
        name: String,
        rule: BreakpointRule,
    ): BreakpointWrite {
        val id = (breakpoints.value.maxOfOrNull { it.id } ?: 0) + 1
        breakpoints.update { it + Breakpoint(id, appId, id, name, true, rule.copy(id = id), 0, 0) }
        return BreakpointWrite.Saved(id)
    }

    override suspend fun update(
        id: Long,
        change: BreakpointChange,
    ): BreakpointWrite {
        if (breakpoints.value.none { it.id == id }) return BreakpointWrite.UnknownBreakpoint
        breakpoints.update { list ->
            list.map {
                if (it.id !=
                    id
                ) {
                    it
                } else {
                    it.copy(name = change.name ?: it.name, enabled = change.enabled ?: it.enabled, rule = change.rule ?: it.rule)
                }
            }
        }
        return BreakpointWrite.Saved(id)
    }

    override suspend fun delete(id: Long) {
        breakpoints.update { list -> list.filterNot { it.id == id } }
    }
}

private class FakeHeldCalls : HeldCallRepository {
    override val held = MutableStateFlow<List<HeldCall>>(emptyList())

    val answers = mutableListOf<String>()

    override fun resume(
        call: HeldCall,
        edit: HeldEdit,
    ): Boolean {
        answers += "resume ${call.requestId} ${edit.url}"
        held.update { it - call }
        return true
    }

    override fun abort(
        call: HeldCall,
        message: String,
    ): Boolean {
        answers += "abort ${call.requestId} $message"
        held.update { it - call }
        return true
    }
}
