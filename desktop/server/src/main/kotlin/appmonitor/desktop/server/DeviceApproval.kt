package appmonitor.desktop.server

import appmonitor.v1.Hello
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Decides whether a device that sent [Hello] gets `HelloAck` (ADR-0018); may suspend while someone decides. */
fun interface DeviceApproval {
    suspend fun approve(hello: Hello): Boolean

    companion object {
        /** Admits every device: the headless server has nobody to ask. */
        val ApproveAll = DeviceApproval { true }
    }
}

/** What a decision is remembered by: one app on one device (ADR-0018). */
data class DeviceKey(
    val deviceId: String,
    val appId: String,
)

/** A device waiting for a decision; [hello] is the first one that asked. */
data class PendingDevice(
    val key: DeviceKey,
    val hello: Hello,
)

/**
 * Asks once per [DeviceKey] and remembers the answer until this instance is dropped. Connections of the same key
 * share one pending entry; it disappears when the last of them is cancelled (the device left) or on [decide].
 */
class DeviceApprovals : DeviceApproval {
    private val lock = Any()

    private val decisions = mutableMapOf<DeviceKey, Boolean>()

    private val waiting = mutableMapOf<DeviceKey, Waiting>()

    private val mutablePending = MutableStateFlow<List<PendingDevice>>(emptyList())

    /** Devices waiting for [decide], in arrival order. */
    val pending: StateFlow<List<PendingDevice>> = mutablePending.asStateFlow()

    override suspend fun approve(hello: Hello): Boolean {
        val key = DeviceKey(hello.device_id, hello.app_id)
        val entry =
            synchronized(lock) {
                decisions[key]?.let { return it }
                waiting
                    .getOrPut(key) {
                        Waiting().also { mutablePending.update { it + PendingDevice(key, hello) } }
                    }.also { it.waiters++ }
            }
        try {
            return entry.decision.await()
        } finally {
            synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0 && waiting[key] === entry) {
                    waiting.remove(key)
                    mutablePending.update { list -> list.filterNot { it.key == key } }
                }
            }
        }
    }

    /** Remembers [approved] for [key] and answers every connection waiting on it. */
    fun decide(
        key: DeviceKey,
        approved: Boolean,
    ) {
        val entry =
            synchronized(lock) {
                decisions[key] = approved
                waiting.remove(key).also { mutablePending.update { list -> list.filterNot { it.key == key } } }
            }
        entry?.decision?.complete(approved)
    }

    private class Waiting {
        val decision = CompletableDeferred<Boolean>()
        var waiters = 0
    }
}
