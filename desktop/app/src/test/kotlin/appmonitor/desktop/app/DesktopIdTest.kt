package appmonitor.desktop.app

import appmonitor.desktop.server.STOP_TIMEOUT
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals

// ADR-0168: devices pin this id, so a restart, and "Clear all data", must keep it.
class DesktopIdTest {
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun theDesktopIdIsCreatedOnceAndKeptThroughAClear() {
        val dir = Files.createTempDirectory("desktop-id")
        try {
            val first = openServices(dir) {}.also { runBlocking { it.storage.close(STOP_TIMEOUT) } }.desktopId
            val second = openServices(dir) {}.also { runBlocking { it.storage.close(STOP_TIMEOUT) } }.desktopId

            assertEquals(first, UUID.fromString(first).toString())
            assertEquals(first, second)

            openServices(dir) {}.also { runBlocking { clearAllKeepingDesktopId(it.storage, it.desktopId) } }.storage.let {
                runBlocking { it.close(STOP_TIMEOUT) }
            }
            assertEquals(first, openServices(dir) {}.also { runBlocking { it.storage.close(STOP_TIMEOUT) } }.desktopId)
        } finally {
            dir.deleteRecursively()
        }
    }
}
