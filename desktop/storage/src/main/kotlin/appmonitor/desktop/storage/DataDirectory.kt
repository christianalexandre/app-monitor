package appmonitor.desktop.storage

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the desktop keeps `appmonitor.db` (ADR-0085): `APPMONITOR_DATA_DIR` when set,
 * else the per-user data directory of the OS named by `os.name`.
 */
fun dataDirectory(
    env: (String) -> String? = System::getenv,
    property: (String) -> String? = System::getProperty,
): Path {
    env("APPMONITOR_DATA_DIR")?.takeIf { it.isNotEmpty() }?.let { return Paths.get(it) }
    val home = Paths.get(property("user.home") ?: error("user.home is not set"))
    val os = property("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("mac") -> {
            home.resolve("Library/Application Support/$MACOS_BUNDLE_ID")
        }

        os.startsWith("windows") -> {
            val local = env("LOCALAPPDATA")?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
            (local ?: home.resolve("AppData").resolve("Local")).resolve("App Monitor")
        }

        else -> {
            val xdg = env("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
            (xdg ?: home.resolve(".local").resolve("share")).resolve("app-monitor")
        }
    }
}

// ADR-0055: the packaged app's bundle id; changing it moves every user's history.
private const val MACOS_BUNDLE_ID = "io.github.christianalexandre.appmonitor.desktop"
