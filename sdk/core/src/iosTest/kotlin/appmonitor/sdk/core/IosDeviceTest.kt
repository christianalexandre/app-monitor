package appmonitor.sdk.core

import appmonitor.v1.Platform
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IosDeviceTest {
    private val dir = Path(SystemTemporaryDirectory, "ios-device-${Uuid.random()}")

    @AfterTest
    fun tearDown() {
        if (SystemFileSystem.exists(dir)) {
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun theDeviceIdFileIsTheSwiftSdks() {
        assertTrue(deviceIdFile().toString().endsWith("/Library/Application Support/appmonitor-device-id"), deviceIdFile().toString())
    }

    @Test
    fun readsTheSwiftSdkDeviceIdFile() {
        // What `Device.swift` writes: a lowercase UUID, no newline.
        val swiftId = Uuid.random().toString().lowercase()
        SystemFileSystem.createDirectories(dir)
        val file = Path(dir, DEVICE_ID_FILE_NAME)
        SystemFileSystem.sink(file).buffered().use { it.writeString(swiftId) }

        assertEquals(swiftId, iosHello(file).device_id)
    }

    @Test
    fun helloNamesTheSimulatorModelAndIos() {
        val hello = iosHello(Path(dir, DEVICE_ID_FILE_NAME))

        assertEquals(Platform.PLATFORM_IOS, hello.platform)
        assertTrue(hello.os_version.startsWith("iOS "), hello.os_version)
        assertTrue(hello.device_name.endsWith(" Simulator"), hello.device_name)
        assertTrue(hello.app_id.isNotEmpty())
        assertEquals(SDK_VERSION, hello.sdk_version)
        assertEquals("kotlin", hello.sdk_name)
        assertEquals("", hello.app_name)
    }

    @Test
    fun helloCarriesTheAppNameOfTheOptions() {
        assertEquals("Sample", iosHello(Path(dir, DEVICE_ID_FILE_NAME), appName = "Sample").app_name)
    }

    @Test
    fun deviceNameIsTheModelNeverTheUserSetName() {
        assertEquals("iPhone17,1 Simulator", deviceName(mapOf("SIMULATOR_MODEL_IDENTIFIER" to "iPhone17,1")))
    }
}
