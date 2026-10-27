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
 * Asks once per [DeviceKey] and remembers the answer, starting from [remembered], until [forget]. Connections of the
 * same key share one pending entry; it disappears when the last of them is cancelled (the device left) or on [decide].
 * A key [disconnect]ed is refused without asking, whatever its answer, until [reconnect] (ADR-0144).
 */
class DeviceApprovals(
    remembered: Map<DeviceKey, Boolean> = emptyMap(),
) : DeviceApproval {
    private val lock = Any()

    private val decisions = remembered.toMutableMap()

    private val waiting = mutableMapOf<DeviceKey, Waiting>()

    private val mutablePending = MutableStateFlow<List<PendingDevice>>(emptyList())

    private val mutableDisconnected = MutableStateFlow<Map<DeviceKey, Hello>>(emptyMap())

    /** Devices waiting for [decide], in arrival order. */
    val pending: StateFlow<List<PendingDevice>> = mutablePending.asStateFlow()

    /** Keys refused until [reconnect], in disconnect order, with the [Hello] given to [disconnect]; held in memory only. */
    val disconnected: StateFlow<Map<DeviceKey, Hello>> = mutableDisconnected.asStateFlow()

    override suspend fun approve(hello: Hello): Boolean {
        val key = DeviceKey(hello.device_id, hello.app_id)
        val entry =
            synchronized(lock) {
                if (key in mutableDisconnected.value) return false
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

    /**
     * Refuses [hello]'s key from now on without asking, and answers its connections still waiting with a refusal it does
     * not remember; returns the key.
     */
    fun disconnect(hello: Hello): DeviceKey {
        val key = DeviceKey(hello.device_id, hello.app_id)
        val entry =
            synchronized(lock) {
                mutableDisconnected.update { if (key in it) it else it + (key to hello) }
                waiting.remove(key).also { mutablePending.update { list -> list.filterNot { it.key == key } } }
            }
        entry?.decision?.complete(false)
        return key
    }

    /** Lets [key] in again as its answer says; a key never answered is asked again. */
    fun reconnect(key: DeviceKey) {
        synchronized(lock) { mutableDisconnected.update { it - key } }
    }

    /** Drops the answer for [key], so its next [approve] asks again; returns the answer dropped, if any. */
    fun forget(key: DeviceKey): Boolean? = synchronized(lock) { decisions.remove(key) }

    /** [forget] for every key; returns the answers dropped. */
    fun forgetAll(): Map<DeviceKey, Boolean> =
        synchronized(lock) {
            decisions.toMap().also { decisions.clear() }
        }

    private class Waiting {
        val decision = CompletableDeferred<Boolean>()
        var waiters = 0
    }
}
