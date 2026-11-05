package appmonitor.desktop.storage

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DataDirectoryLockTest {
    @Test
    fun aSecondProcessIsRefusedUntilTheFirstCloses() =
        withDataDir { dir ->
            val first = Storage.open(dir)
            assertEquals(REFUSED, openInAnotherProcess(dir))
            runBlocking { first.close(5.seconds) }
            assertEquals(OPENED, openInAnotherProcess(dir))
        }

    @Test
    fun aKilledProcessLeavesTheDirectoryFree() =
        withDataDir { dir ->
            val holder = anotherProcess(dir, "hold")
            assertEquals(OPENED, holder.inputReader().readLine())
            assertFailsWith<DataDirectoryInUseException> { Storage.open(dir) }
            holder.destroyForcibly().waitFor()
            runBlocking { Storage.open(dir).close(5.seconds) }
        }

    @Test
    fun aRefusalLeavesTheFilesAndTheFirstStoreAlone() =
        withDataDir { dir ->
            val first = Storage.open(dir)
            assertEquals(REFUSED, openInAnotherProcess(dir))
            runBlocking { first.close(5.seconds) }
            assertEquals(listOf("${first.runId}"), dir.queryStrings("SELECT id FROM runs"))
        }

    @Test
    fun aRefusalInsideTheProcessKeepsTheLock() =
        withDataDir { dir ->
            val first = Storage.open(dir)
            assertFailsWith<DataDirectoryInUseException> { Storage.open(dir) }
            assertEquals(REFUSED, openInAnotherProcess(dir))
            runBlocking { first.close(5.seconds) }
            assertEquals(OPENED, openInAnotherProcess(dir))
            runBlocking { Storage.open(dir).close(5.seconds) }
        }

    @Test
    fun aLinkToTheDirectoryIsTheSameDirectory() =
        withDataDir { dir ->
            val link = dir.resolveSibling("${dir.fileName}-link").createSymbolicLinkPointingTo(dir)
            val first = Storage.open(dir)
            assertFailsWith<DataDirectoryInUseException> { Storage.open(link) }
            assertEquals(REFUSED, openInAnotherProcess(dir))
            runBlocking { first.close(5.seconds) }
        }

    private fun openInAnotherProcess(dir: Path): String {
        val process = anotherProcess(dir, "once")
        val line = process.inputReader().readLine()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return line
    }

    private fun anotherProcess(
        dir: Path,
        mode: String,
    ): Process =
        ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            "appmonitor.desktop.storage.OpenStoreKt",
            dir.absolutePathString(),
            mode,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
}
