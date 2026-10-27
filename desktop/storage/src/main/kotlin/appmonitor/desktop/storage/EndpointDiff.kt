package appmonitor.desktop.storage

import kotlin.math.abs

/** A p50 or p95 known on both sides moved when it changed by at least this many ms and [MIN_MOVE_PERCENT] of the base. */
const val MIN_MOVE_MS = 10L

/** See [MIN_MOVE_MS]. */
const val MIN_MOVE_PERCENT = 20L

/** One endpoint in both runs; [isChange] when the count or error rate differs, or p50 or p95 moved. */
class EndpointChange(
    val endpoint: Endpoint,
    val base: EndpointTally,
    val run: EndpointTally,
) {
    val isChange =
        base.count != run.count ||
            base.errorRate != run.errorRate ||
            moved(base.durationStats?.p50, run.durationStats?.p50) ||
            moved(base.durationStats?.p95, run.durationStats?.p95)
}

/**
 * Two groupings compared per endpoint (SDD 09): [added] in run only, [gone] from it, both in the grouping's order;
 * [changed] largest error rate change first, then largest p95 move; [unchanged] counted.
 */
class EndpointDiff(
    val added: List<EndpointTally>,
    val gone: List<EndpointTally>,
    val changed: List<EndpointChange>,
    val unchanged: Int,
    val truncated: Boolean,
)

fun diffEndpoints(
    base: EndpointGroups,
    run: EndpointGroups,
): EndpointDiff {
    val baseByEndpoint = base.endpoints.associateBy { it.endpoint }
    val runEndpoints = run.endpoints.mapTo(HashSet()) { it.endpoint }
    val (changed, unchanged) =
        run.endpoints
            .mapNotNull { tally -> baseByEndpoint[tally.endpoint]?.let { EndpointChange(tally.endpoint, it, tally) } }
            .partition { it.isChange }
    return EndpointDiff(
        added = run.endpoints.filter { it.endpoint !in baseByEndpoint },
        gone = base.endpoints.filter { it.endpoint !in runEndpoints },
        changed =
            changed.sortedWith(
                compareByDescending<EndpointChange> { abs(it.run.errorRate - it.base.errorRate) }
                    .thenByDescending { abs((it.run.durationStats?.p95 ?: 0) - (it.base.durationStats?.p95 ?: 0)) }
                    .thenBy { it.endpoint.toString() },
            ),
        unchanged = unchanged.size,
        truncated = base.truncated || run.truncated,
    )
}

/** A duration known on one side only moved; known on both, it moved by at least [MIN_MOVE_MS] and [MIN_MOVE_PERCENT]. */
private fun moved(
    base: Long?,
    run: Long?,
): Boolean {
    if (base == null || run == null) return base != run
    val move = abs(run - base)
    return move >= MIN_MOVE_MS && move * 100 >= base * MIN_MOVE_PERCENT
}
