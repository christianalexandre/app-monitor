package appmonitor.desktop.server

import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Upper bound on a [stop] run at exit: past it the hook returns and the JVM halts with the stop unfinished. */
val STOP_TIMEOUT: Duration = 5.seconds

/**
 * A shutdown hook that runs [stop] on a daemon thread and gives up after [timeout]. Any failure of [stop],
 * [Error]s included, goes to [onFailure]; a timeout is reported as a [TimeoutException].
 */
fun shutdownHook(
    timeout: Duration = STOP_TIMEOUT,
    onFailure: (Throwable) -> Unit = { System.err.println("Server stop failed: $it") },
    stop: suspend () -> Unit,
): Thread =
    Thread {
        val worker =
            thread(isDaemon = true, name = "app-monitor-stop") {
                try {
                    runBlocking { stop() }
                } catch (e: Throwable) {
                    onFailure(e)
                }
            }
        worker.join(timeout.inWholeMilliseconds)
        if (worker.isAlive) onFailure(TimeoutException("stop did not return within $timeout"))
    }
