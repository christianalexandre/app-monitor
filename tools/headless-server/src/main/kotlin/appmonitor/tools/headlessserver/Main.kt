package appmonitor.tools.headlessserver

import appmonitor.desktop.proxy.ProxyCa
import appmonitor.desktop.proxy.ProxyServer
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.Drops
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.server.shutdownHook
import appmonitor.desktop.server.startWithShutdownHook
import appmonitor.protocol.Protocol
import appmonitor.v1.BreakpointHit
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
import java.net.BindException
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.system.exitProcess

/** Headless server: logs every session and envelope to stdout until the process is stopped. */
fun main(args: Array<String>) {
    val options = parseArgs(args, System.err) ?: exitProcess(2)
    val uncaught = Thread.getDefaultUncaughtExceptionHandler()
    // Ktor's CIO also throws a taken port at this handler, a stack trace beside startOrNull's line.
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        if (failure !is BindException) uncaught?.uncaughtException(thread, failure) ?: failure.printStackTrace()
    }
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
    val breakpoints =
        options.breakpointsFile?.let { file ->
            try {
                readBreakpoints(file.readText())
            } catch (e: IOException) {
                System.err.println("Cannot read $file: ${e.message}")
                exitProcess(2)
            } catch (e: IllegalArgumentException) {
                System.err.println("Bad breakpoints in $file: ${e.message}")
                exitProcess(2)
            }
        }
    val server = AppMonitorServer(ServerConfig(port = options.port, desktopId = options.desktopId))
    runBlocking {
        val bound =
            startOrNull(options.port, System.err) { startWithShutdownHook(server, Runtime.getRuntime()::addShutdownHook) }
                ?: exitProcess(1)
        println("Listening on port $bound, path ${Protocol.WEBSOCKET_PATH}; mDNS on ${server.advertisedAddresses}")
        server.advertisingProblem?.let { println("mDNS: $it") }
        options.desktopId?.let { println("Desktop id $it") }
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
                if (breakpoints != null &&
                    session.send(breakpointRulesEnvelope(breakpoints.rules, System.currentTimeMillis()))
                ) {
                    println("[${session.id}] sent ${breakpoints.rules.rules.size} breakpoints")
                }
                var replayAsked = false
                var printedDrops: Drops? = null
                val printDrops = {
                    session.drops.value?.takeIf { it != printedDrops }?.let {
                        printedDrops = it
                        println("[${session.id}] ${describe(it)}")
                    }
                }
                session.envelopes.collect { envelope ->
                    println("[${session.id}] ${describe(envelope)}")
                    printDrops()
                    breakpoints?.let { breakpointResume(envelope, it) }?.let { resume ->
                        if (session.send(breakpointResumeEnvelope(resume, System.currentTimeMillis()))) {
                            println("[${session.id}] ${describe(resume)}")
                        }
                    }
                    val asked = if (replayAsked) null else options.replayPath?.let { replayTarget(envelope, it) }
                    if (asked != null) {
                        replayAsked = true
                        if (session.send(replayRequestEnvelope(asked, System.currentTimeMillis()))) {
                            println("[${session.id}] asked a replay of $asked")
                        }
                    }
                }
                printDrops()
                println("[${session.id}] disconnected")
            }
        }
    }
}

/** What [start] returned; null after one line on [err] when the port cannot be bound. */
internal suspend fun startOrNull(
    port: Int,
    err: PrintStream,
    start: suspend () -> Int,
): Int? =
    try {
        start()
    } catch (e: BindException) {
        err.println("Cannot listen on port $port: ${e.message}")
        null
    }

internal data class ServerArgs(
    val port: Int = Protocol.DEFAULT_PORT,
    val proxyDirectory: Path? = null,
    val mocksFile: Path? = null,
    val replayPath: String? = null,
    val desktopId: String? = null,
    val breakpointsFile: Path? = null,
)

/**
 * `--port`, `--proxy DIR` (ADR-0109), `--mocks FILE` (ADR-0116), `--replay PATH` (ADR-0122), `--desktop-id UUID`
 * (ADR-0168), `--breakpoints FILE` (ADR-0188); null after one line on [err].
 */
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
                "--breakpoints" -> options.copy(breakpointsFile = Path.of(value))
                "--replay" -> options.copy(replayPath = value.takeIf { it.startsWith("/") } ?: return null)
                "--desktop-id" -> options.copy(desktopId = value.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return null)
                else -> return null
            }
    }
    return options
}

private const val USAGE =
    "Usage: server [--port PORT] [--proxy DIR] [--mocks FILE] [--breakpoints FILE] [--replay PATH] [--desktop-id UUID], " +
        "PORT from 0 (ephemeral) to 65535, DIR the proxy's CA directory, FILE mock rules or breakpoints as JSON, " +
        "PATH a URL path starting with /, UUID the id devices pin"

private const val MAX_PORT = 65_535

/** ADR-0166: `drops: evicted N, oversized N, lost N`, `lost unknown` while a previous session is open or not known here. */
internal fun describe(drops: Drops): String =
    "drops: evicted ${drops.evicted}, oversized ${drops.oversized}, lost ${drops.lost ?: "unknown"}"

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

                "interaction/BreakpointHit" -> {
                    BreakpointHit.ADAPTER.decode(envelope.payload).run { "${request?.request_id} $stage rule $rule_id" }
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
