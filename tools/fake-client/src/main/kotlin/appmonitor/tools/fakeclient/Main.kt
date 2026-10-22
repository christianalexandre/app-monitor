package appmonitor.tools.fakeclient

import appmonitor.protocol.Protocol
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.system.exitProcess

private const val USAGE = """Usage: fake-client [--host HOST] [--port PORT] [--name SERVICE] [--timeout MS]

Replays protocol/fixtures to a desktop. Without --host, discovers it over mDNS
(${Protocol.MDNS_SERVICE_TYPE}), optionally only the service named SERVICE."""

fun main(args: Array<String>) {
    if ("--help" in args) exit(USAGE, 0)
    val options = parse(args) ?: exit(USAGE, 2)
    runBlocking {
        val target =
            options.host?.let { Target(it, options.port ?: Protocol.DEFAULT_PORT) }
                ?: discover(options.timeoutMs, options.serviceName)?.let { found -> options.port?.let { found.copy(port = it) } ?: found }
                ?: exit("No desktop found over mDNS in ${options.timeoutMs} ms; pass --host", 1)
        println("Connecting to ws://${target.host}:${target.port}${target.path}")
        val ack =
            try {
                replay(target)
            } catch (e: IOException) {
                exit("Cannot reach the desktop: $e", 1)
            } catch (e: HandshakeException) {
                exit(e.message.orEmpty(), 1)
            }
        println("Session ${ack.session_id}: sent ${Fixtures.traffic.size} envelopes")
    }
}

private class Options(
    val host: String?,
    val port: Int?,
    val serviceName: String?,
    val timeoutMs: Long,
)

private fun parse(args: Array<String>): Options? {
    val values = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (key !in setOf("--host", "--port", "--name", "--timeout") || i + 1 >= args.size) return null
        values[key] = args[i + 1]
        i += 2
    }
    return Options(
        host = values["--host"],
        port = values["--port"]?.let { it.toIntOrNull() ?: return null },
        serviceName = values["--name"],
        timeoutMs = values["--timeout"]?.let { it.toLongOrNull() ?: return null } ?: 10_000L,
    )
}

private fun exit(
    message: String,
    status: Int,
): Nothing {
    System.err.println(message)
    exitProcess(status)
}
