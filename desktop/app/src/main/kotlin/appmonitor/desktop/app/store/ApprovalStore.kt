package appmonitor.desktop.app.store

import appmonitor.desktop.domain.AnsweredDevice
import appmonitor.desktop.domain.AnsweredDeviceRepository
import appmonitor.desktop.domain.ApprovalRepository
import appmonitor.desktop.domain.DeviceConnectionRepository
import appmonitor.desktop.domain.DisconnectedDevice
import appmonitor.desktop.domain.PendingApproval
import appmonitor.desktop.server.DeviceApprovals
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.storage.Storage
import appmonitor.v1.Hello
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The developer's answers per (`device_id`, `app_id`), kept in [storage] across restarts (ADR-0083), and the pairs
 * disconnected, in memory (ADR-0144). [disconnect] closes the open sessions of a pair.
 */
internal class ApprovalStore private constructor(
    private val storage: Storage,
    /** What the server asks; holds the stored answers from the start. */
    val approvals: DeviceApprovals,
    private val disconnect: (DeviceKey) -> Unit,
) : ApprovalRepository,
    AnsweredDeviceRepository,
    DeviceConnectionRepository {
    override val pending: Flow<List<PendingApproval>> =
        approvals.pending.map { list -> list.map { PendingApproval(it.key.deviceId, it.key.appId, it.hello) } }

    override val answered: Flow<List<AnsweredDevice>> =
        storage.observeApprovals().map { list ->
            list.map { AnsweredDevice(it.deviceId, it.appId, it.approved, it.decidedAtMs, it.deviceName, it.osVersion, it.appVersion) }
        }

    override val disconnected: Flow<List<DisconnectedDevice>> =
        approvals.disconnected.map { map -> map.map { (key, hello) -> DisconnectedDevice(key.deviceId, key.appId, hello) } }

    // Refused before the close: a Hello racing it is not admitted.
    override suspend fun disconnect(hello: Hello) = disconnect.invoke(approvals.disconnect(hello))

    override suspend fun reconnect(
        deviceId: String,
        appId: String,
    ) = approvals.reconnect(DeviceKey(deviceId, appId))

    override suspend fun decide(
        device: PendingApproval,
        approved: Boolean,
    ) {
        approvals.decide(DeviceKey(device.deviceId, device.appId), approved)
        storage.decided(device.hello, approved)
    }

    override suspend fun forget(
        deviceId: String,
        appId: String,
    ) = forget(DeviceKey(deviceId, appId))

    /** The pair's next `Hello` is asked for again; its open sessions are closed when it was approved. */
    suspend fun forget(key: DeviceKey) {
        if (approvals.forget(key) == true) disconnect(key)
        storage.forget(key.deviceId, key.appId)
    }

    override suspend fun forgetAll() {
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
