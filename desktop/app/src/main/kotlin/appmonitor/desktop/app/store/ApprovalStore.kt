package appmonitor.desktop.app.store

import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.PendingDevice
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.StoredApproval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The developer's answers per (`device_id`, `app_id`), kept in [storage] across restarts (ADR-0083). [disconnect]
 * closes the open sessions of a pair that was approved and is forgotten.
 */
internal class ApprovalStore private constructor(
    private val storage: Storage,
    /** What the server asks; holds the stored answers from the start. */
    val approvals: DeviceApprovals,
    private val disconnect: (DeviceKey) -> Unit,
) {
    /** Devices waiting for [decide], in arrival order. */
    val pending: StateFlow<List<PendingDevice>> get() = approvals.pending

    /** Every stored answer, again after each change. */
    val stored: Flow<List<StoredApproval>> = storage.observeApprovals()

    /** Answers every connection of [device] at once, then stores the answer. */
    suspend fun decide(
        device: PendingDevice,
        approved: Boolean,
    ) {
        approvals.decide(device.key, approved)
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
