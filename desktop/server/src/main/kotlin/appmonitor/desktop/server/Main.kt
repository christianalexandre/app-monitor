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
import java.io.PrintStream
import kotlin.system.exitProcess

/** Headless server: logs every session and envelope to stdout until the process is stopped. */
fun main(args: Array<String>) {
    val port = parsePort(args, System.err) ?: exitProcess(2)
    val server = AppMonitorServer(ServerConfig(port = port))
    runBlocking {
        val bound = startWithShutdownHook(server, Runtime.getRuntime()::addShutdownHook)
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

/** Registers a hook that stops [server] before starting it, so a shutdown during [AppMonitorServer.start] still releases it. */
internal suspend fun startWithShutdownHook(
    server: AppMonitorServer,
    addShutdownHook: (Thread) -> Unit,
): Int {
    addShutdownHook(Thread { runBlocking { server.stop() } })
    return server.start()
}

/** The `--port` value, or [Protocol.DEFAULT_PORT]; on a bad argument prints one line on [err] and returns null. */
internal fun parsePort(
    args: Array<String>,
    err: PrintStream,
): Int? {
    val port =
        when {
            args.isEmpty() -> return Protocol.DEFAULT_PORT
            args.size == 2 && args[0] == "--port" -> args[1].toIntOrNull()?.takeIf { it in 0..MAX_PORT }
            else -> null
        }
    if (port == null) err.println(USAGE)
    return port
}

private const val USAGE = "Usage: server [--port PORT], PORT from 0 (ephemeral) to 65535"

private const val MAX_PORT = 65_535

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
