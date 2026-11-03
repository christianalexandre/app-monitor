package appmonitor.desktop.storage

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal const val OPENED = "opened"
internal const val REFUSED = "refused"

/** Run by [DataDirectoryLockTest] in its own JVM: a lock held in one process says nothing about another's. */
fun main(args: Array<String>) {
    val storage =
        try {
            Storage.open(Path.of(args[0]))
        } catch (_: DataDirectoryInUseException) {
            println(REFUSED)
            return
        }
    println(OPENED)
    System.out.flush()
    if (args[1] == "hold") Thread.sleep(60_000)
    runBlocking { storage.close(5.seconds) }
}
