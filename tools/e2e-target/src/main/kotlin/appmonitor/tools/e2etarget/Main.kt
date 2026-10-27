package appmonitor.tools.e2etarget

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE =
    "Usage: e2e-target [--port PORT] [--grpc-trust FILE], PORT from 0 (ephemeral, the default) to 65535, " +
        "FILE the trust store written for the gRPC origin"

/**
 * Serves until the process is stopped; prints `Listening on port N`, with `--grpc-trust` then `gRPC on port G`,
 * then one line per request.
 */
fun main(args: Array<String>) {
    val options =
        options(args) ?: run {
            System.err.println(USAGE)
            exitProcess(2)
        }
    runBlocking {
        val server = e2eTarget(options.port) { println(it) }.startSuspend(wait = false)
        val grpc = options.grpcTrust?.let { file -> GrpcTarget().also { it.trustStore(file) } }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                grpc?.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            },
        )
        println("Listening on port ${server.engine.resolvedConnectors().first().port}")
        grpc?.let { println("gRPC on port ${it.port}") }
        awaitCancellation()
    }
}

internal data class Options(
    val port: Int = 0,
    val grpcTrust: Path? = null,
)

/** The options of [args], or null when one is unknown, has no value or a bad one. */
internal fun options(args: Array<String>): Options? {
    if (args.size % 2 != 0) return null
    var options = Options()
    for ((name, value) in args.toList().chunked(2)) {
        options =
            when (name) {
                "--port" -> options.copy(port = value.toIntOrNull()?.takeIf { it in 0..65_535 } ?: return null)
                "--grpc-trust" -> options.copy(grpcTrust = Path.of(value))
                else -> return null
            }
    }
    return options
}
