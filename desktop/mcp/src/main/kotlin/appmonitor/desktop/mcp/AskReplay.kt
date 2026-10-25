package appmonitor.desktop.mcp

import appmonitor.desktop.storage.Storage
import appmonitor.v1.CaptureLayer
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A replay an agent asked, for the developer to send or refuse (ADR-0121). [sessionId] is the server's session id. */
data class AskedReplay(
    val exchangeId: Long,
    val sessionId: String,
    val requestId: String,
    val method: String?,
    val url: String?,
    val deviceName: String,
    val appId: String,
)

/** What [ReplayAsks.ask] did with a replay. */
enum class ReplayAnswer {
    /** It waits for the developer; asked again while it waits, it is the same one. */
    WAITING,

    /** The exchange's session is over: a replay goes through the app. */
    SESSION_ENDED,

    /** The device did not accept `interaction`. */
    NOT_TAKEN,

    /** [ReplayAsks.MAX_WAITING] replays already wait. */
    TOO_MANY,
}

/** Where `replay_request` leaves a replay until the developer answers it in the window (ADR-0121 A.1). */
fun interface ReplayAsks {
    fun ask(replay: AskedReplay): ReplayAnswer

    companion object {
        const val MAX_WAITING = 20
    }
}

/** `replay_request` (SDD 10, ADR-0121 C.1): answers at once; nothing reaches the device before the developer's click (ADR-0005). */
internal object AskReplay {
    const val NAME = "replay_request"

    val description =
        "Asks the app to send a stored HTTP exchange again through its own client, as it did the first time. The " +
            "request waits until the developer clicks Send again in App Monitor's window, or refuses it; this tool " +
            "answers at once with status waiting. Once sent, the new call shows in search_requests with replay true " +
            "and replay_of this id. The app must still be connected in the same session. A replayed POST or other " +
            "non-GET runs again on the server. Not for a connection, a WebSocket or an exchange of the desktop's proxy."

    val inputSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "The exchange's id, as search_requests gives it.")
                    }
                },
            required = listOf("id"),
        )

    suspend fun call(
        storage: Storage,
        asks: ReplayAsks?,
        arguments: JsonObject?,
    ): JsonObject {
        val id = arguments.long("id", 1..Long.MAX_VALUE, null) ?: throw ToolError("id is required")
        val detail = storage.exchange(id) ?: throw ToolError("no exchange with id $id")
        val exchange = detail.exchange
        when {
            exchange.isConnection -> {
                throw ToolError("exchange $id is a connection: it has no HTTP request to send again")
            }

            exchange.captureLayer == CaptureLayer.CAPTURE_LAYER_PROXY -> {
                throw ToolError("exchange $id went through the desktop's proxy: only an app sends a request again")
            }

            exchange.url?.startsWith("ws") == true -> {
                throw ToolError("exchange $id is a WebSocket: it cannot be sent again")
            }
        }
        if (asks == null) throw ToolError("replay is not available")
        // Deleted between the two reads, as retention or "Clear all data" can.
        val session = storage.session(exchange.sessionId) ?: throw ToolError("no exchange with id $id")
        val asked =
            AskedReplay(
                exchangeId = id,
                sessionId = session.serverSessionId,
                requestId = exchange.requestId,
                method = exchange.method,
                url = exchange.url,
                deviceName = session.hello.device_name,
                appId = session.hello.app_id,
            )
        when (asks.ask(asked)) {
            ReplayAnswer.WAITING -> {
                Unit
            }

            ReplayAnswer.SESSION_ENDED -> {
                throw ToolError("exchange $id's session ended: a replay goes through the app, so only a connected one can take it")
            }

            ReplayAnswer.NOT_TAKEN -> {
                throw ToolError("the app of exchange $id does not take replays")
            }

            ReplayAnswer.TOO_MANY -> {
                throw ToolError("${ReplayAsks.MAX_WAITING} replays already wait for the developer; ask again once they are answered")
            }
        }
        return buildJsonObject {
            putJsonObject("replay") {
                put("exchange_id", id)
                put("status", "waiting")
            }
        }
    }
}
