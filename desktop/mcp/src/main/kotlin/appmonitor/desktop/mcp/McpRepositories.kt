package appmonitor.desktop.mcp

import appmonitor.desktop.domain.BenchmarkRepository
import appmonitor.desktop.domain.IssueRepository
import appmonitor.desktop.domain.MockRepository
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.TrafficQueryRepository

/** What the tools read and write through `desktop/domain` (ADR-0133, ADR-0138); `desktop/app` builds it over the store. */
class McpRepositories(
    val runs: RunRepository,
    val queries: TrafficQueryRepository,
    val benchmarks: BenchmarkRepository,
    val issues: IssueRepository,
    val mocks: MockRepository,
)
