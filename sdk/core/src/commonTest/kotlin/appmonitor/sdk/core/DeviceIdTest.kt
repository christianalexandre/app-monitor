package appmonitor.sdk.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeviceIdTest {
    private val dir = Files.createTempDirectory("device-id").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun isStableAcrossStarts() {
        val file = File(dir, "nested/appmonitor-device-id")

        val first = deviceId(file)

        assertEquals(first, deviceId(file))
        UUID.fromString(first)
    }

    @Test
    fun aCorruptFileIsReplaced() {
        val file = File(dir, "appmonitor-device-id").apply { writeText("not a uuid") }

        val id = deviceId(file)

        assertNotEquals("not a uuid", id)
        assertEquals(id, file.readText())
    }
}
