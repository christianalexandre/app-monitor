package appmonitor.desktop.domain

import appmonitor.v1.Hello
import kotlinx.coroutines.flow.Flow

/** What the desktop calls the app: `Hello.app_name`, else its `app_id` (ADR-0165). */
val Hello.appLabel: String get() = app_name.ifEmpty { app_id }

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

/** One app on one device the developer disconnected; [hello] is the one of the session it was disconnected from. */
data class DisconnectedDevice(
    val deviceId: String,
    val appId: String,
    val hello: Hello,
)

/** Devices the developer disconnected: refused without a prompt until reconnected, until the desktop quits (ADR-0144). */
interface DeviceConnectionRepository {
    /** In disconnect order, again after each change. */
    val disconnected: Flow<List<DisconnectedDevice>>

    /** Closes the sessions of [hello]'s app on its device and refuses its next connections. */
    suspend fun disconnect(hello: Hello)

    /** Lets the device in again as its answer says. */
    suspend fun reconnect(
        deviceId: String,
        appId: String,
    )
}
