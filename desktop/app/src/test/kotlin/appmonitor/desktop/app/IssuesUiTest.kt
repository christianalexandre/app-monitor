package appmonitor.desktop.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.feature.insights.CONFIRM_DELETE_ISSUE
import appmonitor.desktop.feature.insights.ISSUE_NOTE
import appmonitor.desktop.feature.insights.ISSUE_TAGS
import appmonitor.desktop.feature.insights.ISSUE_TITLE
import appmonitor.desktop.feature.insights.SAVE_ISSUE
import appmonitor.desktop.feature.traffic.ISSUES
import appmonitor.desktop.feature.traffic.MARK
import appmonitor.desktop.feature.traffic.SAVE_AS_ISSUE
import appmonitor.desktop.storage.IssueSource
import appmonitor.desktop.storage.IssueStatus
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredIssue
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class IssuesUiTest {
    @Test
    fun `requests saved as an issue are listed, filtered, edited and kept across a reopen`() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            storage.record(
                envelope(RequestStarted(request_id = "r1", method = "POST", url = "https://api.example.com/cart")),
                envelope(ResponseReceived(request_id = "r1", status_code = 500)),
                envelope(RequestStarted(request_id = "r2", method = "GET", url = "https://api.example.com/cart")),
                envelope(ResponseReceived(request_id = "r2", status_code = 200)),
                envelope(RequestStarted(request_id = "r3", method = "GET", url = "https://api.example.com/feed")),
            )
            val feed = runBlocking { storage.allExchanges().single { it.requestId == "r3" }.id }
            runBlocking { storage.createIssue("Feed never answers", "", listOf("backend"), listOf(feed), IssueSource.MCP) }

            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { AppMonitorApp(storage, "Listening") }

                awaitText("https://api.example.com/feed")
                onNodeWithTag(MARK + "r1").performClick()
                onNodeWithTag(MARK + "r2").performClick()
                awaitText("Save 2 as issue")
                onNodeWithTag(SAVE_AS_ISSUE).performClick()
                awaitText("2 requests")
                onNodeWithText("POST https://api.example.com/cart").assertExists()
                onNodeWithTag(ISSUE_TITLE).performTextReplacement("Cart fails then succeeds")
                onNodeWithTag(ISSUE_NOTE).performTextReplacement("500 on the first try")
                onNodeWithTag(ISSUE_TAGS).performTextReplacement("checkout, flaky")
                onNodeWithTag(SAVE_ISSUE).performClick()

                awaitText("Cart fails then succeeds")
                onNodeWithText("Open · checkout, flaky · 2 requests · in the window", substring = true).assertExists()
                onNodeWithText("Open · backend · 1 request · by MCP", substring = true).assertExists()
                onNodeWithText("Save as issue").assertExists()
                screenshot("issues.png")

                onNodeWithText("Tag: Any ▾").performClick()
                onNodeWithText("checkout").performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { !exists("Feed never answers") }

                onNodeWithText("Cart fails then succeeds").performClick()
                awaitText("Delete")
                onNodeWithText("Status: Open ▾").performClick()
                onNodeWithText("Resolved").performClick()
                onNodeWithTag(ISSUE_NOTE).performTextReplacement("500 on the first try, fixed by the retry")
                onNodeWithTag(SAVE_ISSUE).performClick()
                awaitText("Resolved · checkout, flaky", substring = true)

                onNodeWithText("Tag: checkout ▾").performClick()
                onNodeWithText("Any").performClick()
                awaitText("Feed never answers")
                onNodeWithText("Feed never answers").performClick()
                awaitText("Delete")
                onNodeWithText("Delete").performClick()
                onNodeWithTag(CONFIRM_DELETE_ISSUE).performClick()
                waitUntil(timeoutMillis = TIMEOUT_MS) { !exists("Feed never answers") }
            }
            runBlocking { storage.close(5.seconds) }

            val reopened = Storage.open(dir)
            try {
                val issue = runBlocking { reopened.observeIssues().first() }.single()
                assertEquals(
                    StoredIssue(
                        issue.id,
                        "Cart fails then succeeds",
                        "500 on the first try, fixed by the retry",
                        listOf("checkout", "flaky"),
                        IssueStatus.RESOLVED,
                        IssueSource.WINDOW,
                        issue.createdAtMs,
                        issue.updatedAtMs,
                        2,
                    ),
                    issue,
                )
                runDesktopComposeUiTest(width = 1280, height = 800) {
                    setContent { AppMonitorApp(reopened, "Listening") }
                    onNodeWithTag(ISSUES).performClick()
                    awaitText("Cart fails then succeeds")
                }
            } finally {
                runBlocking { reopened.close(5.seconds) }
            }
        }

    @Test
    fun `a status edit in the window keeps an mcp tag that holds a comma`() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            try {
                storage.record(envelope(RequestStarted(request_id = "r1", method = "GET", url = "https://api.example.com/feed")))
                val feed = runBlocking { storage.allExchanges().single().id }
                runBlocking { storage.createIssue("Feed times out", "", listOf("timeout, retry"), listOf(feed), IssueSource.MCP) }

                runDesktopComposeUiTest(width = 1280, height = 800) {
                    setContent { AppMonitorApp(storage, "Listening") }
                    onNodeWithTag(ISSUES).performClick()
                    awaitText("Feed times out")
                    onNodeWithText("Feed times out").performClick()
                    awaitText("Delete")
                    onNodeWithText("Status: Open ▾").performClick()
                    onNodeWithText("Resolved").performClick()
                    onNodeWithTag(SAVE_ISSUE).performClick()
                    awaitText("Resolved · timeout, retry", substring = true)
                }

                val issue = runBlocking { storage.observeIssues().first() }.single()
                assertEquals(IssueStatus.RESOLVED, issue.status)
                assertEquals(listOf("timeout, retry"), issue.tags)
            } finally {
                runBlocking { storage.close(5.seconds) }
            }
        }

    private fun ComposeUiTest.exists(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
