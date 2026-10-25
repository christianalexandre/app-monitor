package appmonitor.desktop.domain

import appmonitor.v1.Hello
import kotlinx.coroutines.flow.Flow

/** One app on one device waiting for the developer's answer; [hello] is the first one that asked (ADR-0083). */
data class PendingApproval(
    val deviceId: String,
    val appId: String,
    val hello: Hello,
)

/** The devices waiting to be admitted, and the developer's answer to each (ADR-0018, ADR-0083). */
interface ApprovalRepository {
    /** In arrival order, again after each change. */
    val pending: Flow<List<PendingApproval>>

    /** Answers every connection of [device] at once and remembers the answer. */
    suspend fun decide(
        device: PendingApproval,
        approved: Boolean,
    )
}
