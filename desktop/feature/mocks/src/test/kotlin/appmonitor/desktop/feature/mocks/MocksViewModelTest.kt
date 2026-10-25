package appmonitor.desktop.feature.mocks

import appmonitor.desktop.domain.Mock
import appmonitor.desktop.domain.MockChange
import appmonitor.desktop.domain.MockRepository
import appmonitor.desktop.domain.MockSource
import appmonitor.desktop.domain.MockWrite
import appmonitor.protocol.Protocol
import appmonitor.v1.MockResponse
import appmonitor.v1.MockRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.ByteString.Companion.toByteString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MocksViewModelTest {
    private val repository = FakeMocks()

    @BeforeTest
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `counts the rules to confirm and confirms one`() =
        runTest {
            repository.mocks.value = listOf(mock(1, pending = true), mock(2))
            val viewModel = MocksViewModel(repository)
            assertEquals(1, viewModel.state.value.pending)
            viewModel.confirm(1)
            assertEquals(0, viewModel.state.value.pending)
        }

    @Test
    fun `marks the active rules past one frame, per app`() =
        runTest {
            val big = MockRule(host = "api.test", path = "/", response = MockResponse(body = ByteArray(BIG).toByteString()))
            repository.mocks.value =
                listOf(
                    mock(1, rule = big),
                    mock(2, rule = big),
                    mock(3, rule = big),
                    mock(4, app = "other", rule = big),
                    mock(5, rule = big, enabled = false),
                )
            assertEquals(setOf(3L), MocksViewModel(repository).state.value.cut)
        }

    @Test
    fun `turns a rule off, saves and deletes`() =
        runTest {
            repository.mocks.value = listOf(mock(1))
            val viewModel = MocksViewModel(repository)
            viewModel.toggle(
                viewModel.state.value.mocks
                    .single(),
            )
            assertEquals(
                false,
                viewModel.state.value.mocks
                    .single()
                    .enabled,
            )

            val rule = MockRule(host = "api.test", path = "/new", response = MockResponse(status_code = 201))
            assertNull(viewModel.save(null, "app", "new", rule))
            assertEquals(
                MockSource.WINDOW,
                repository.mocks.value
                    .last()
                    .source,
            )
            assertEquals("Not saved: the rule was deleted", viewModel.save(mock(9), "app", "gone", rule))

            viewModel.delete(1)
            assertEquals(
                listOf("new"),
                viewModel.state.value.mocks
                    .map { it.name },
            )
        }
}

// Three of these fill more than one frame together, two do not.
private val BIG = (Protocol.MAX_FRAME_BYTES / 3 + 1_000).toInt()

private fun mock(
    id: Long,
    app: String = "app",
    pending: Boolean = false,
    enabled: Boolean = true,
    rule: MockRule = MockRule(host = "api.test", path = "/$id", response = MockResponse(status_code = 200)),
) = Mock(id, app, id, "rule $id", enabled, pending, MockSource.WINDOW, rule.copy(id = id), 0, 0)

private class FakeMocks : MockRepository {
    val mocks = MutableStateFlow<List<Mock>>(emptyList())

    override fun observeMocks(): Flow<List<Mock>> = mocks

    override suspend fun mocks(): List<Mock> = mocks.value

    override suspend fun create(
        appId: String,
        name: String,
        rule: MockRule,
        source: MockSource,
    ): MockWrite {
        val id = (mocks.value.maxOfOrNull { it.id } ?: 0) + 1
        mocks.update { it + Mock(id, appId, id, name, true, source == MockSource.MCP, source, rule.copy(id = id), 0, 0) }
        return MockWrite.Saved(id)
    }

    override suspend fun update(
        id: Long,
        change: MockChange,
    ): MockWrite {
        if (mocks.value.none { it.id == id }) return MockWrite.UnknownMock
        mocks.update { list ->
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
        return MockWrite.Saved(id)
    }

    override suspend fun confirm(id: Long): MockWrite {
        mocks.update { list -> list.map { if (it.id == id) it.copy(pending = false) else it } }
        return MockWrite.Saved(id)
    }

    override suspend fun delete(id: Long) {
        mocks.update { list -> list.filterNot { it.id == id } }
    }
}
