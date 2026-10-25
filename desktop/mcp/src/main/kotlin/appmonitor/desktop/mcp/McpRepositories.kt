package appmonitor.desktop.mcp

import appmonitor.desktop.domain.BenchmarkRepository
import appmonitor.desktop.domain.RunRepository
import appmonitor.desktop.domain.TrafficQueryRepository

/** What the tools read through `desktop/domain` (ADR-0133, ADR-0138); `desktop/app` builds it over the store. */
class McpRepositories(
    val runs: RunRepository,
    val queries: TrafficQueryRepository,
    val benchmarks: BenchmarkRepository,
)
