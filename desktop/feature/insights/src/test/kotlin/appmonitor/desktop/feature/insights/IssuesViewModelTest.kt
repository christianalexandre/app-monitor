package appmonitor.desktop.feature.insights

import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.Issue
import appmonitor.desktop.domain.IssueChange
import appmonitor.desktop.domain.IssueFilter
import appmonitor.desktop.domain.IssueRepository
import appmonitor.desktop.domain.IssueSource
import appmonitor.desktop.domain.IssueStatus
import appmonitor.desktop.domain.IssueWrite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IssuesViewModelTest {
    private val issues = FakeIssues()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the filter keeps the issues of its status and tag, every issue stays in all`() =
        runTest {
            issues.all.value =
                listOf(
                    issueOf(2, IssueStatus.RESOLVED, "checkout"),
                    issueOf(1, IssueStatus.OPEN, "checkout", "flaky"),
                )
            issues.tags.value = listOf("checkout", "flaky")
            val viewModel = IssuesViewModel(issues)
            assertEquals(
                listOf(2L, 1L),
                viewModel.state.value.issues
                    .map { it.id },
            )
            assertEquals(listOf("checkout", "flaky"), viewModel.state.value.tags)

            viewModel.setFilter(IssueFilter(tag = "flaky"))
            assertEquals(
                listOf(1L),
                viewModel.state.value.issues
                    .map { it.id },
            )
            viewModel.setFilter(IssueFilter(status = IssueStatus.RESOLVED, tag = "checkout"))
            assertEquals(
                listOf(2L),
                viewModel.state.value.issues
                    .map { it.id },
            )
            assertEquals(
                listOf(2L, 1L),
                viewModel.state.value.all
                    .map { it.id },
            )
            assertEquals(1, issues.observed, "one query for the list and the open issue")
        }

    @Test
    fun `a second save while one is written writes nothing`() =
        runTest {
            val viewModel = IssuesViewModel(issues)
            val slow = CompletableDeferred<IssueWrite>()
            issues.next = slow
            val first = async(start = CoroutineStart.UNDISPATCHED) { viewModel.create("Cart fails", "", listOf("checkout"), setOf(7)) }
            assertTrue(viewModel.state.value.saving)

            assertNull(viewModel.create("Cart fails", "", listOf("checkout"), setOf(7)))
            assertNull(viewModel.update(1, IssueChange(note = "again")))
            slow.complete(IssueWrite.Saved(1))
            assertEquals(IssueWrite.Saved(1), first.await())
            assertFalse(viewModel.state.value.saving)
            assertEquals(listOf("create Cart fails [checkout] [7]"), issues.writes)

            issues.next = CompletableDeferred(IssueWrite.UnknownIssue)
            assertEquals(IssueWrite.UnknownIssue, viewModel.update(1, IssueChange(status = IssueStatus.RESOLVED)))
            viewModel.delete(1)
            assertEquals(
                listOf("create Cart fails [checkout] [7]", "update 1 ${IssueChange(status = IssueStatus.RESOLVED)}", "delete 1"),
                issues.writes,
            )
        }
}

private fun issueOf(
    id: Long,
    status: IssueStatus,
    vararg tags: String,
) = Issue(id, "Issue $id", "", tags.toList(), status, IssueSource.WINDOW, id, id, 1)

private class FakeIssues : IssueRepository {
    val all = MutableStateFlow(emptyList<Issue>())
    val tags = MutableStateFlow(emptyList<String>())
    val writes = mutableListOf<String>()
    var next = CompletableDeferred<IssueWrite>(IssueWrite.Saved(1))
    var observed = 0

    override fun observeIssues(): Flow<List<Issue>> = all.also { observed++ }

    override fun observeTags(): Flow<List<String>> = tags

    override suspend fun exchanges(id: Long): List<Exchange> = emptyList()

    override suspend fun create(
        title: String,
        note: String,
        tags: Collection<String>,
        exchangeIds: Collection<Long>,
    ): IssueWrite {
        writes += "create $title $tags $exchangeIds"
        return next.await()
    }

    override suspend fun update(
        id: Long,
        change: IssueChange,
    ): IssueWrite {
        writes += "update $id $change"
        return next.await()
    }

    override suspend fun delete(id: Long) {
        writes += "delete $id"
    }
}
