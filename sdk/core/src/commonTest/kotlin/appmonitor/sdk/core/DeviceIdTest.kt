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
    fun theFirstDesktopPinnedStays() {
        val pin = FileDesktopPin(Path(dir, DESKTOP_PIN_FILE_NAME))
        val first = Uuid.random().toString()

        assertEquals(null, pin.pinned())
        assertEquals(first, pin.pin(first))
        assertEquals(first, pin.pin(Uuid.random().toString()))
        assertEquals(first, FileDesktopPin(Path(dir, DESKTOP_PIN_FILE_NAME)).pinned())
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
                val left = SystemFileSystem.list(Path(dir, "$round")).map { it.name }.filterNot { it == "appmonitor-device-id.lock" }
                assertEquals(listOf("appmonitor-device-id"), left)
            }
        }

    @Test
    fun racingWritersThatCannotLinkAllReturnTheIdLeftInTheFile() =
        runTest {
            repeat(ROUNDS) { round ->
                val file = Path(dir, "unlinked-$round", "appmonitor-device-id")

                val ids =
                    withContext(Dispatchers.Default) {
                        List(WRITERS) { async { deviceId(file, link = { _, _ -> null }) } }.awaitAll()
                    }

                val stored = SystemFileSystem.source(file).buffered().use { it.readString() }
                assertEquals(List(WRITERS) { stored }, ids, "round $round")
                assertEquals(
                    setOf("appmonitor-device-id", "appmonitor-device-id.lock"),
                    SystemFileSystem.list(Path(dir, "unlinked-$round")).map { it.name }.toSet(),
                )
            }
        }

    @Test
    fun aCorruptFileIsReplacedWithoutALink() {
        SystemFileSystem.createDirectories(dir)
        val file = Path(dir, "appmonitor-device-id")
        SystemFileSystem.sink(file).buffered().use { it.writeString("not a uuid") }

        val id = deviceId(file, link = { _, _ -> null })

        assertNotEquals("not a uuid", id)
        assertEquals(id, SystemFileSystem.source(file).buffered().use { it.readString() })
    }

    @Test
    fun aWriterThatLinksTakesNoLock() {
        val file = Path(dir, "appmonitor-device-id")
        val links = mutableListOf<Path>()

        val id =
            deviceId(file) { existing, target ->
                links += target
                SystemFileSystem.atomicMove(existing, target)
                true
            }

        assertEquals(listOf(file), links)
        assertEquals(id, stored(file))
        assertEquals(listOf("appmonitor-device-id"), SystemFileSystem.list(dir).map { it.name })
    }

    @Test
    fun aWriterThatLosesTheLinkKeepsTheWinnersIdWithoutALock() {
        val file = Path(dir, "appmonitor-device-id")
        val winner = Uuid.random().toString()

        val id =
            deviceId(file) { _, target ->
                SystemFileSystem.sink(target).buffered().use { it.writeString(winner) }
                false
            }

        assertEquals(winner, id)
        assertEquals(listOf("appmonitor-device-id"), SystemFileSystem.list(dir).map { it.name })
    }

    // ADR-0080: what a linking writer left before the lock is re-read under it.
    @Test
    fun aLockingWriterKeepsTheIdALinkingWriterLeft() {
        val file = Path(dir, "appmonitor-device-id")
        val linked = Uuid.random().toString()

        val id =
            deviceId(file) { _, target ->
                SystemFileSystem.sink(target).buffered().use { it.writeString(linked) }
                null
            }

        assertEquals(linked, id)
        assertEquals(linked, stored(file))
        assertEquals(
            setOf("appmonitor-device-id", "appmonitor-device-id.lock"),
            SystemFileSystem.list(dir).map { it.name }.toSet(),
        )
    }

    private fun stored(file: Path): String = SystemFileSystem.source(file).buffered().use { it.readString() }

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
