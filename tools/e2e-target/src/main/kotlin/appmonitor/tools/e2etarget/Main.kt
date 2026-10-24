package appmonitor.tools.e2etarget

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private const val USAGE = "Usage: e2e-target [--port PORT], PORT from 0 (ephemeral, the default) to 65535"

/** Serves until the process is stopped; prints `Listening on port N`, then one line per request. */
fun main(args: Array<String>) {
    val port =
        when {
            args.isEmpty() -> 0
            args.size == 2 && args[0] == "--port" -> args[1].toIntOrNull()?.takeIf { it in 0..65_535 }
            else -> null
        } ?: run {
            System.err.println(USAGE)
            exitProcess(2)
        }
    runBlocking {
        val server = e2eTarget(port) { println(it) }.startSuspend(wait = false)
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000) })
        println("Listening on port ${server.engine.resolvedConnectors().first().port}")
        awaitCancellation()
    }
}
