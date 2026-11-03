package appmonitor.tools.fakeclient

import appmonitor.protocol.Protocol
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.exitProcess

private const val USAGE = """Usage: fake-client [--host HOST] [--port PORT] [--name SERVICE] [--timeout MS] [--handshake-timeout MS]

Replays protocol/fixtures to a desktop. Without --host, discovers it over mDNS
(${Protocol.MDNS_SERVICE_TYPE}), optionally only the service named SERVICE. --timeout bounds
discovery; --handshake-timeout bounds the wait for HelloAck, a device approval included
($HANDSHAKE_TIMEOUT_MS by default)."""

fun main(args: Array<String>) {
    exitProcess(runBlocking { run(args, System.out, System.err) })
}

/** Runs the CLI and returns its exit status; every failure is one line on [err], never a stack trace. */
internal suspend fun run(
    args: Array<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val options = parse(args) ?: return err.fail(USAGE, 2)
    if (options.help) return out.fail(USAGE, 0)
    val target =
        try {
            options.host?.let { Target(it, options.port ?: Protocol.DEFAULT_PORT) }
                ?: discover(options.timeoutMs, options.serviceName)?.let { found -> options.port?.let { found.copy(port = it) } ?: found }
                ?: return err.fail("No desktop found over mDNS in ${options.timeoutMs} ms; pass --host", 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return err.fail("mDNS discovery failed: ${e.oneLine()}", 1)
        }
    val url = "ws://${target.host}:${target.port}${target.path}"
    out.println("Connecting to $url; a new device waits for its approval on the desktop, up to ${options.handshakeTimeoutMs} ms")
    val ack =
        try {
            replay(target, handshakeTimeoutMs = options.handshakeTimeoutMs)
        } catch (e: HandshakeException) {
            return err.fail(e.message.orEmpty(), 1)
        } catch (e: SessionClosedException) {
            return err.fail(e.message.orEmpty(), 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return err.fail("Cannot reach the desktop at $url: ${e.oneLine()}", 1)
        }
    out.println("Session ${ack.session_id}: sent ${Fixtures.traffic.size} envelopes")
    return 0
}

private class Options(
    val host: String?,
    val port: Int?,
    val serviceName: String?,
    val timeoutMs: Long,
    val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    val help: Boolean = false,
)

private fun parse(args: Array<String>): Options? {
    val values = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (key == "--help") return Options(null, null, null, 0, help = true)
        if (key !in setOf("--host", "--port", "--name", "--timeout", "--handshake-timeout") || i + 1 >= args.size) return null
        values[key] = args[i + 1]
        i += 2
    }
    return Options(
        host = values["--host"],
        port = values["--port"]?.let { it.toIntOrNull()?.takeIf { p -> p in 1..65_535 } ?: return null },
        serviceName = values["--name"],
        timeoutMs = values["--timeout"]?.let { it.toLongOrNull()?.takeIf { ms -> ms > 0 } ?: return null } ?: 10_000L,
        handshakeTimeoutMs =
            values["--handshake-timeout"]?.let { it.toLongOrNull()?.takeIf { ms -> ms > 0 } ?: return null } ?: HANDSHAKE_TIMEOUT_MS,
    )
}

private fun PrintStream.fail(
    message: String,
    status: Int,
): Int {
    println(message)
    return status
}

private fun Exception.oneLine(): String = toString().lines().first()
