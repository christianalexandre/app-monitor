package appmonitor.desktop.app.store

import appmonitor.desktop.domain.ApprovalRepository
import appmonitor.desktop.domain.PendingApproval
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredApproval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The developer's answers per (`device_id`, `app_id`), kept in [storage] across restarts (ADR-0083). [disconnect]
 * closes the open sessions of a pair that was approved and is forgotten.
 */
internal class ApprovalStore private constructor(
    private val storage: Storage,
    /** What the server asks; holds the stored answers from the start. */
    val approvals: DeviceApprovals,
    private val disconnect: (DeviceKey) -> Unit,
) : ApprovalRepository {
    override val pending: Flow<List<PendingApproval>> =
        approvals.pending.map { list -> list.map { PendingApproval(it.key.deviceId, it.key.appId, it.hello) } }

    /** Every stored answer, again after each change. */
    val stored: Flow<List<StoredApproval>> = storage.observeApprovals()

    override suspend fun decide(
        device: PendingApproval,
        approved: Boolean,
    ) {
        approvals.decide(DeviceKey(device.deviceId, device.appId), approved)
        storage.decided(device.hello, approved)
    }

    /** The pair's next `Hello` is asked for again; its open sessions are closed when it was approved. */
    suspend fun forget(key: DeviceKey) {
        if (approvals.forget(key) == true) disconnect(key)
        storage.forget(key.deviceId, key.appId)
    }

    suspend fun forgetAll() {
        approvals.forgetAll().forEach { (key, approved) -> if (approved) disconnect(key) }
        storage.forgetAll()
    }

    companion object {
        /** Reads the stored answers from [storage]; [disconnect] is called only after [open] returns. */
        suspend fun open(
            storage: Storage,
            disconnect: (DeviceKey) -> Unit,
        ): ApprovalStore {
            val remembered = storage.approvals().associate { DeviceKey(it.deviceId, it.appId) to it.approved }
            return ApprovalStore(storage, DeviceApprovals(remembered), disconnect)
        }
    }
}
