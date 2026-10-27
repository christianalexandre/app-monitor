package appmonitor.desktop.storage

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class DataDirectoryTest {
    @Test
    fun overrideWinsWhenNotEmpty() {
        assertEquals(Paths.get("/data"), resolve(env = mapOf("APPMONITOR_DATA_DIR" to "/data"), os = "Linux"))
        assertEquals(Paths.get("/home/u/.local/share/app-monitor"), resolve(env = mapOf("APPMONITOR_DATA_DIR" to ""), os = "Linux"))
    }

    @Test
    fun macOsUsesApplicationSupport() {
        assertEquals(
            Paths.get("/home/u/Library/Application Support/io.github.christianalexandre.appmonitor.desktop"),
            resolve(os = "Mac OS X"),
        )
    }

    @Test
    fun linuxUsesAnAbsoluteXdgDataHome() {
        assertEquals(Paths.get("/xdg/app-monitor"), resolve(env = mapOf("XDG_DATA_HOME" to "/xdg"), os = "Linux"))
        assertEquals(Paths.get("/home/u/.local/share/app-monitor"), resolve(env = mapOf("XDG_DATA_HOME" to "relative"), os = "Linux"))
    }

    @Test
    fun windowsUsesLocalAppData() {
        assertEquals(Paths.get("/local", "App Monitor"), resolve(env = mapOf("LOCALAPPDATA" to "/local"), os = "Windows 11"))
        assertEquals(Paths.get("/home/u", "AppData", "Local", "App Monitor"), resolve(os = "Windows 11"))
    }

    private fun resolve(
        env: Map<String, String> = emptyMap(),
        os: String,
    ) = dataDirectory(env = env::get, property = mapOf("user.home" to "/home/u", "os.name" to os)::get)
}
