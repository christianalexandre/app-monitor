package appmonitor.sdk.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class DeviceIdTest {
    private val dir = Path(SystemTemporaryDirectory, "device-id-${Uuid.random()}")

    @AfterTest
    fun tearDown() {
        deleteRecursively(dir)
    }

    @Test
    fun isStableAcrossStarts() {
        val file = Path(dir, "nested", "appmonitor-device-id")

        val first = deviceId(file)

        assertEquals(first, deviceId(file))
        Uuid.parse(first)
    }

    @Test
    fun aCorruptFileIsReplaced() {
        SystemFileSystem.createDirectories(dir)
        val file = Path(dir, "appmonitor-device-id")
        SystemFileSystem.sink(file).buffered().use { it.writeString("not a uuid") }

        val id = deviceId(file)

        assertNotEquals("not a uuid", id)
        assertEquals(id, SystemFileSystem.source(file).buffered().use { it.readString() })
    }

    @Test
    fun racingWritersOnAnEmptyDirectoryAllReturnTheIdLeftInTheFile() =
        runTest {
            repeat(ROUNDS) { round ->
                val file = Path(dir, "$round", "appmonitor-device-id")

                val ids = withContext(Dispatchers.Default) { List(WRITERS) { async { deviceId(file) } }.awaitAll() }

                val stored = SystemFileSystem.source(file).buffered().use { it.readString() }
                assertEquals(List(WRITERS) { stored }, ids, "round $round")
                assertEquals(listOf("appmonitor-device-id"), SystemFileSystem.list(Path(dir, "$round")).map { it.name })
            }
        }

    private fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::deleteRecursively)
        SystemFileSystem.delete(path)
    }

    private companion object {
        const val ROUNDS = 50
        const val WRITERS = 8
    }
}
