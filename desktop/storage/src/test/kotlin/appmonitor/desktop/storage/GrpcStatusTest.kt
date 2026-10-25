package appmonitor.desktop.storage

import appmonitor.v1.Header
import appmonitor.v1.ResponseReceived
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GrpcStatusTest {
    @Test
    fun aFailedCallIsAnErrorInEveryListReader() =
        withDataDir { dir ->
            val storage = Storage.open(dir) { 1_000 }
            runBlocking {
                storage.connected("s", hello(), null)
                storage.received("s", envelope(started("failed")))
                storage.received("s", envelope(grpcEnded("failed", status = 13)))
                storage.flush()
                val id = storage.searchExchanges(ExchangeFilter()).single().id
                assertIs<IssueWrite.Saved>(storage.createIssue("A", "", emptyList(), listOf(id), IssueSource.WINDOW))
                val session = storage.sessions(storage.runId).single().id
                val rows =
                    listOf(
                        storage.searchExchanges(ExchangeFilter()).single(),
                        storage.exchangesOfSession(session).single(),
                        storage.exchangesOfRun(storage.runId).single(),
                        storage
                            .issueExchanges(
                                storage
                                    .issuePage(IssueFilter())
                                    .single()
                                    .issue.id,
                            ).single(),
                        storage.exchange(id)!!.exchange,
                        storage.searchExchangeSizes(ExchangeFilter()).single().exchange,
                    )
                rows.forEach {
                    assertEquals(200, it.statusCode)
                    assertEquals(13, it.grpcStatus)
                    assertTrue(it.isError)
                    assertFalse(it.failed)
                }
                storage.close(5.seconds)
            }
        }

    @Test
    fun benchmarksAndRunDiffCountAFailedCall() =
        withDataDir { dir ->
            val storage = Storage.open(dir) { 1_000 }
            runBlocking {
                storage.connected("base", hello(), null)
                storage.received("base", envelope(started("ok", url = URL)))
                storage.received("base", envelope(grpcEnded("ok", status = 0)))
                storage.disconnected("base")
                storage.flush()
                val base = storage.runId
                val run = storage.newRun()
                storage.connected("run", hello(), null)
                storage.received("run", envelope(started("failed", url = URL)))
                storage.received("run", envelope(grpcEnded("failed", status = 5)))
                storage.flush()
                val baseGroups = storage.groupByEndpoint(ExchangeFilter(runId = base))
                val runGroups = storage.groupByEndpoint(ExchangeFilter(runId = run))
                assertEquals(0L, baseGroups.endpoints.single().errors)
                assertEquals(1L, runGroups.endpoints.single().errors)
                val change = diffEndpoints(baseGroups, runGroups).changed.single()
                assertEquals(0.0, change.base.errorRate)
                assertEquals(1.0, change.run.errorRate)
                storage.close(5.seconds)
            }
        }

    @Test
    fun theLastGrpcStatusWinsAndNoneIsNull() =
        withDataDir { dir ->
            val storage = Storage.open(dir) { 1_000 }
            runBlocking {
                storage.connected("s", hello(), null)
                storage.received("s", envelope(started("twice")))
                val ended = grpcEnded("twice", status = 0)
                storage.received("s", envelope(ended.copy(headers = ended.headers + Header("Grpc-Status", " 14 "))))
                storage.received("s", envelope(started("http")))
                storage.received("s", envelope(ResponseReceived(request_id = "http", status_code = 200)))
                storage.flush()
                val rows = storage.searchExchanges(ExchangeFilter()).associateBy { it.requestId }
                assertEquals(14, rows.getValue("twice").grpcStatus)
                assertNull(rows.getValue("http").grpcStatus)
                assertFalse(rows.getValue("http").isError)
                storage.close(5.seconds)
            }
        }

    @Test
    fun aVersionTwoFileIsMigrated() =
        withDataDir { dir ->
            Storage.open(dir) { 1_000 }.let { runBlocking { it.close(5.seconds) } }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve(Storage.FILE_NAME)}").use { connection ->
                connection.createStatement().use {
                    it.execute("DROP TABLE mock_rules")
                    it.execute("ALTER TABLE exchanges DROP COLUMN grpc_status")
                    it.execute("ALTER TABLE exchanges DROP COLUMN mock_rule_id")
                    it.execute("DROP TABLE breakpoint_rules")
                    it.execute("ALTER TABLE exchanges DROP COLUMN replay_of")
                    it.execute("PRAGMA user_version = 2")
                }
            }
            val storage = Storage.open(dir) { 2_000 }
            runBlocking {
                storage.connected("s", hello(), null)
                storage.received("s", envelope(started("failed")))
                storage.received("s", envelope(grpcEnded("failed", status = 13)))
                storage.flush()
                assertEquals(13, storage.searchExchanges(ExchangeFilter()).single().grpcStatus)
                storage.close(5.seconds)
            }
            assertEquals(7, dir.queryLong("PRAGMA user_version"))
        }

    private companion object {
        const val URL = "https://api.example.com/test.Echo/Unary"
    }
}
