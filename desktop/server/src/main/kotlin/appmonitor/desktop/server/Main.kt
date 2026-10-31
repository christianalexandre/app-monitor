package appmonitor.desktop.server

import appmonitor.desktop.proxy.ProxyCa
import appmonitor.desktop.proxy.ProxyServer
import appmonitor.protocol.Protocol
import appmonitor.v1.Envelope
import appmonitor.v1.ReplayRefused
import appmonitor.v1.RequestFailed
import appmonitor.v1.RequestStarted
import appmonitor.v1.ResponseReceived
import appmonitor.v1.WebSocketFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

/** Headless server: logs every session and envelope to stdout until the process is stopped. */
fun main(args: Array<String>) {
    val options = parseArgs(args, System.err) ?: exitProcess(2)
    val mocks =
        options.mocksFile?.let { file ->
            try {
                readMockRules(file.readText())
            } catch (e: IOException) {
                System.err.println("Cannot read $file: ${e.message}")
                exitProcess(2)
            } catch (e: IllegalArgumentException) {
                System.err.println("Bad rules in $file: ${e.message}")
                exitProcess(2)
            }
        }
    val server = AppMonitorServer(ServerConfig(port = options.port))
    runBlocking {
        val bound = startWithShutdownHook(server, Runtime.getRuntime()::addShutdownHook)
        println("Listening on port $bound, path ${Protocol.WEBSOCKET_PATH}; mDNS on ${server.advertisedAddresses}")
        options.proxyDirectory?.let { directory ->
            val proxy = ProxyServer(ProxyCa.loadOrCreate(directory), { println("[proxy] ${describe(it)}") }, port = 0)
            Runtime.getRuntime().addShutdownHook(shutdownHook { proxy.stop() })
            println("Proxy on port ${proxy.start()}, CA in ${directory.resolve(ProxyCa.CERTIFICATE_FILE)}")
        }
        server.sessions.collect { session ->
            launch {
                val device = session.hello
                println(
                    "[${session.id}] connected: ${device.device_name} (${device.platform}) ${device.app_id} ${device.app_version} ${device.sdk_name}",
                )
                if (device.app_name.isNotEmpty()) println("[${session.id}] app name: ${device.app_name}")
                if (mocks != null && session.send(mockRulesEnvelope(mocks, System.currentTimeMillis()))) {
                    println("[${session.id}] sent ${mocks.rules.size} mock rules")
                }
                var replayAsked = false
                session.envelopes.collect { envelope ->
                    println("[${session.id}] ${describe(envelope)}")
                    val asked = if (replayAsked) null else options.replayPath?.let { replayTarget(envelope, it) }
                    if (asked != null) {
                        replayAsked = true
                        if (session.send(replayRequestEnvelope(asked, System.currentTimeMillis()))) {
                            println("[${session.id}] asked a replay of $asked")
                        }
                    }
                }
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
    addShutdownHook(shutdownHook { server.stop() })
    return server.start()
}

internal data class ServerArgs(
    val port: Int = Protocol.DEFAULT_PORT,
    val proxyDirectory: Path? = null,
    val mocksFile: Path? = null,
    val replayPath: String? = null,
)

/** `--port`, `--proxy DIR` (ADR-0109), `--mocks FILE` (ADR-0116), `--replay PATH` (ADR-0122); null after one line on [err]. */
internal fun parseArgs(
    args: Array<String>,
    err: PrintStream,
): ServerArgs? {
    val options = serverArgs(args)
    if (options == null) err.println(USAGE)
    return options
}

private fun serverArgs(args: Array<String>): ServerArgs? {
    if (args.size % 2 != 0) return null
    var options = ServerArgs()
    for ((name, value) in args.toList().chunked(2)) {
        options =
            when (name) {
                "--port" -> options.copy(port = value.toIntOrNull()?.takeIf { it in 0..MAX_PORT } ?: return null)
                "--proxy" -> options.copy(proxyDirectory = Path.of(value))
                "--mocks" -> options.copy(mocksFile = Path.of(value))
                "--replay" -> options.copy(replayPath = value.takeIf { it.startsWith("/") } ?: return null)
                else -> return null
            }
    }
    return options
}

private const val USAGE =
    "Usage: server [--port PORT] [--proxy DIR] [--mocks FILE] [--replay PATH], PORT from 0 (ephemeral) to 65535, DIR " +
        "the proxy's CA directory, FILE mock rules as JSON, PATH a URL path starting with /"

private const val MAX_PORT = 65_535

internal fun describe(envelope: Envelope): String {
    val name = "${envelope.channel}/${envelope.type}"
    val detail =
        try {
            when (name) {
                "network/RequestStarted" -> {
                    RequestStarted.ADAPTER.decode(envelope.payload).run {
                        "$request_id $method $url" + (mock_rule_id?.let { " mock $it" } ?: "") +
                            (replay_of?.let { " replay_of $it" } ?: "")
                    }
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

                "interaction/ReplayRefused" -> {
                    ReplayRefused.ADAPTER.decode(envelope.payload).run { "$request_id $reason" }
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
