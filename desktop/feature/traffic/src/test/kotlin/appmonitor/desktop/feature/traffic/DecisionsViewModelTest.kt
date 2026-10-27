package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.ApprovalRepository
import appmonitor.desktop.domain.AskedReplay
import appmonitor.desktop.domain.AskedReplayRepository
import appmonitor.desktop.domain.PendingApproval
import appmonitor.desktop.domain.ReplaySent
import appmonitor.v1.Hello
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DecisionsViewModelTest {
    private val approvals = FakeApprovals()
    private val asked = FakeAsked()

    @BeforeTest
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `shows what waits and passes each answer on`() =
        runTest {
            val device = PendingApproval("d1", "app.a", Hello(device_id = "d1", app_id = "app.a"))
            val replay = AskedReplay(7, "s1", "r1", "POST", "https://api.test/items", "Pixel", "app.a")
            approvals.pending.value = listOf(device)
            asked.asked.value = listOf(replay)
            val viewModel = DecisionsViewModel(approvals, asked)
            assertEquals(DecisionsState(listOf(device), listOf(replay)), viewModel.state.value)

            viewModel.decide(device, approved = true)
            viewModel.answer(replay, send = false)

            assertEquals(listOf(device to true), approvals.decisions)
            assertEquals(listOf(replay to false), asked.answers)
            assertEquals(DecisionsState(), viewModel.state.value)
        }

    @Test
    fun `without repositories nothing waits`() =
        runTest {
            val viewModel = DecisionsViewModel(null, null)
            viewModel.decide(PendingApproval("d1", "app.a", Hello()), approved = true)
            assertEquals(DecisionsState(), viewModel.state.value)
        }
}

private class FakeApprovals : ApprovalRepository {
    override val pending = MutableStateFlow<List<PendingApproval>>(emptyList())
    val decisions = mutableListOf<Pair<PendingApproval, Boolean>>()

    override suspend fun decide(
        device: PendingApproval,
        approved: Boolean,
    ) {
        decisions += device to approved
        pending.update { it - device }
    }
}

private class FakeAsked : AskedReplayRepository {
    override val asked = MutableStateFlow<List<AskedReplay>>(emptyList())
    val answers = mutableListOf<Pair<AskedReplay, Boolean>>()

    override fun answer(
        replay: AskedReplay,
        send: Boolean,
    ): ReplaySent? {
        answers += replay to send
        asked.update { it - replay }
        return if (send) ReplaySent.SENT else null
    }
}
