package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Headless server: logs every session and envelope to stdout until the process is stopped. */
fun main(args: Array<String>) {
    val port =
        args.indexOf("--port").takeIf { it >= 0 }?.let { requireNotNull(args.getOrNull(it + 1)?.toIntOrNull()) { "--port needs a number" } }
            ?: Protocol.DEFAULT_PORT
    val server = AppMonitorServer(ServerConfig(port = port))
    runBlocking {
        val bound = server.start()
        Runtime.getRuntime().addShutdownHook(Thread { runBlocking { server.stop() } })
        println("Listening on port $bound, path ${Protocol.WEBSOCKET_PATH}; mDNS on ${server.advertisedAddresses}")
        server.sessions.collect { session ->
            launch {
                val device = session.hello
                println("[${session.id}] connected: ${device.device_name} (${device.platform}) ${device.app_id} ${device.app_version}")
                session.envelopes.collect { println("[${session.id}] ${describe(it)}") }
                println("[${session.id}] disconnected")
            }
        }
    }
}

internal fun describe(envelope: Envelope): String {
    val name = "${envelope.channel}/${envelope.type}"
    val detail =
        try {
            when (name) {
                "network/RequestStarted" -> {
                    RequestStarted.ADAPTER.decode(envelope.payload).run { "$request_id $method $url" }
                }

                "network/ResponseReceived" -> {
                    ResponseReceived.ADAPTER
                        .decode(
                            envelope.payload,
                        ).run { "$request_id $status_code, ${body.size} bytes" }
                }

                "network/RequestFailed" -> {
                    RequestFailed.ADAPTER.decode(envelope.payload).run { "$request_id $error" }
                }

                "network/WebSocketFrame" -> {
                    WebSocketFrame.ADAPTER
                        .decode(
                            envelope.payload,
                        ).run { "$request_id $direction $opcode, ${payload.size} bytes" }
                }

                else -> {
                    "${envelope.payload.size} bytes"
                }
            }
        } catch (_: IOException) {
            "undecodable payload, ${envelope.payload.size} bytes"
        }
    return "$name $detail"
}
