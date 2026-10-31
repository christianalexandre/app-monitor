package appmonitor.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import androidx.compose.ui.window.rememberWindowState
import appmonitor.desktop.app.store.ApprovalStore
import appmonitor.desktop.app.store.McpController
import appmonitor.desktop.app.store.ProxyController
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.app.ui.AppMonitorApp
import appmonitor.desktop.data.appearanceRepository
import appmonitor.desktop.data.paneLayoutRepository
import appmonitor.desktop.data.runRepository
import appmonitor.desktop.data.settingRepository
import appmonitor.desktop.data.trafficRepository
import appmonitor.desktop.domain.AppearanceRepository
import appmonitor.desktop.domain.PaneLayoutRepository
import appmonitor.desktop.domain.ThemeMode
import appmonitor.desktop.feature.settings.StorageUnavailable
import appmonitor.desktop.mcp.McpAccess
import appmonitor.desktop.mcp.McpServer
import appmonitor.desktop.proxy.ProxyServer
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.DeviceSession
import appmonitor.desktop.server.STOP_TIMEOUT
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.server.printStopFailure
import appmonitor.desktop.server.shutdownHook
import appmonitor.desktop.storage.LiveTraffic
import appmonitor.desktop.storage.Recorder
import appmonitor.desktop.storage.Storage
import appmonitor.desktop.storage.dataDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Dimension
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.time.Duration

fun main() {
    val directory = dataDirectory()
    lateinit var server: AppMonitorServer
    val (storage, approvals, access, appearance, panes, desktopId) =
        try {
            openServices(directory) { server.disconnect(it) }
        } catch (e: Exception) {
            exitAfter { runBlocking { showStorageFailure(directory.resolve(Storage.FILE_NAME).toString(), e) } }
            return
        }
    // ADR-0150: read by AWT once, before the first window; set after that it does nothing.
    System.setProperty(MAC_APPEARANCE, macAppearanceOf(appearance.theme.value))
    val live = LiveTraffic()
    val recorder = Recorder(live, storage)
    val store = TrafficStore(storage, recorder)
    val runRepository = storage.runRepository()
    val traffic = storage.trafficRepository(live)
    var status by mutableStateOf("Starting server…")
    server = AppMonitorServer(ServerConfig(desktopId = desktopId), approvals.approvals)
    val mcp = McpController(storage, live, access, replays = store.replays)
    val proxy = ProxyController(storage, recorder, directory)
    val stopServer = once(server::stop)
    val closeStorage = once { storage.close(STOP_TIMEOUT) }
    addStopHook(timeout = STOP_TIMEOUT * 2 + McpServer.GRACE + McpServer.STOP_TIMEOUT + ProxyServer.STOP_TIMEOUT) {
        stopAll({ stopBoth(mcp::close, proxy::close) }, stopServer, closeStorage)
    }
    exitAfter {
        runBlocking {
            try {
                runUntilExit(
                    serve = {
                        coroutineScope {
                            launch { mcp.start() }
                            launch { proxy.start() }
                            runServer(server, store::collect, stop = stopServer) { status = it }
                        }
                    },
                ) {
                    awaitApplication {
                        Window(
                            onCloseRequest = ::exitApplication,
                            title = "App Monitor",
                            state = rememberWindowState(width = 1280.dp, height = 800.dp),
                        ) {
                            // ADR-0151: narrower, the panes' minimums would squeeze the detail to nothing.
                            LaunchedEffect(window) { window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT) }
                            val scope = rememberCoroutineScope()
                            AppMonitorApp(
                                storage,
                                status,
                                approvals,
                                answered = approvals,
                                connections = approvals,
                                onClearAll = {
                                    scope.launch {
                                        approvals.forgetAll()
                                        clearAllKeepingDesktopId(storage, desktopId)
                                        appearance.setTheme(appearance.theme.value)
                                        panes.setLayout(panes.layout.value)
                                        recorder.forget()
                                        live.clear()
                                        mcp.afterClearAll()
                                        proxy.afterClearAll()
                                    }
                                },
                                mcp = mcp,
                                proxy = proxy,
                                heldCalls = store.heldCalls,
                                replays = store.replays,
                                appearance = appearance,
                                panes = panes,
                                live = live,
                                runRepository = runRepository,
                                traffic = traffic,
                            )
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { stopAll({ stopBoth(mcp::close, proxy::close) }, stopServer, closeStorage) }
            }
        }
    }
}

internal const val MAC_APPEARANCE = "apple.awt.application.appearance"

/** The macOS appearance of the window's title bar, menus and system dialogs for [theme]. */
internal fun macAppearanceOf(theme: ThemeMode): String =
    when (theme) {
        ThemeMode.System -> "system"
        ThemeMode.Light -> "NSAppearanceNameAqua"
        ThemeMode.Dark -> "NSAppearanceNameDarkAqua"
    }

// The devices pane, the request list and the detail at their minimums (ADR-0151), with room to spare.
private const val MIN_WIDTH = 1000
private const val MIN_HEIGHT = 600

internal data class Services(
    val storage: Storage,
    val approvals: ApprovalStore,
    val access: McpAccess,
    val appearance: AppearanceRepository,
    val panes: PaneLayoutRepository,
    /** The id devices pin (ADR-0168), created once and kept in the store's `settings`. */
    val desktopId: String,
)

internal const val DESKTOP_ID_KEY = "desktop.id"

/** "Clear all data" empties `settings` too; the id devices pinned is written back, or every one would refuse this desktop (ADR-0168). */
internal suspend fun clearAllKeepingDesktopId(
    storage: Storage,
    desktopId: String,
) {
    storage.clearAll()
    storage.settingRepository().setSetting(DESKTOP_ID_KEY, desktopId)
}

/** Opens the store, its device answers, the MCP token, the theme, the panes' layout and the desktop id; on a failure the store is closed again and no server starts. */
internal fun openServices(
    directory: Path,
    disconnect: (DeviceKey) -> Unit,
): Services {
    val storage = Storage.open(directory, startRun = false)
    return try {
        runBlocking {
            val settings = storage.settingRepository()
            Services(
                storage,
                ApprovalStore.open(storage, disconnect),
                McpAccess.open(settings),
                settings.appearanceRepository(),
                settings.paneLayoutRepository(),
                settings.setting(DESKTOP_ID_KEY) ?: UUID.randomUUID().toString().also { settings.setSetting(DESKTOP_ID_KEY, it) },
            )
        }
    } catch (e: Exception) {
        runBlocking { storage.close(STOP_TIMEOUT) }
        throw e
    }
}

/** Runs [first], then [second] even when [first] fails. */
internal suspend fun stopBoth(
    first: suspend () -> Unit,
    second: suspend () -> Unit,
) {
    try {
        first()
    } finally {
        second()
    }
}

/** Stops [mcp], then [server], each even when the other fails, and only then runs [closeStorage] (SDD 10). */
internal suspend fun stopAll(
    mcp: suspend () -> Unit,
    server: suspend () -> Unit,
    closeStorage: suspend () -> Unit,
) {
    try {
        try {
            mcp()
        } finally {
            server()
        }
    } finally {
        closeStorage()
    }
}

private suspend fun showStorageFailure(
    file: String,
    failure: Exception,
) {
    failure.printStackTrace()
    awaitApplication {
        Window(onCloseRequest = ::exitApplication, title = "App Monitor") {
            StorageUnavailable(file, failure.message ?: failure.toString()) { clearDataFiles(Path.of(file)) }
        }
    }
}

/** Deletes the database and its WAL and shared-memory files (ADR-0082 D.1); returns what to tell the developer. */
internal fun clearDataFiles(file: Path): String =
    try {
        listOf("", "-wal", "-shm").forEach { Files.deleteIfExists(file.resolveSibling(file.fileName.toString() + it)) }
        "Data deleted. Quit and reopen App Monitor."
    } catch (e: IOException) {
        "Could not delete the files: ${e.message ?: e}"
    }

/** Runs [block], then calls [exit] with 0, or with 1 after printing the failure when [block] throws. */
internal fun exitAfter(
    exit: (Int) -> Unit = ::exitProcess,
    block: () -> Unit,
) {
    val code =
        try {
            block()
            0
        } catch (e: Throwable) {
            e.printStackTrace()
            1
        }
    exit(code)
}

/** Runs [serve] while [ui] runs; once [ui] returns or fails, cancels [serve] and waits for it, its cleanup included. */
internal suspend fun runUntilExit(
    serve: suspend () -> Unit,
    ui: suspend () -> Unit,
) = coroutineScope {
    val serving = launch(Dispatchers.Default) { serve() }
    try {
        ui()
    } finally {
        withContext(NonCancellable) { serving.cancelAndJoin() }
    }
}

/** [stop] run by the first caller only; later calls return at once, so exit waits for one [STOP_TIMEOUT] at most. */
internal fun once(stop: suspend () -> Unit): suspend () -> Unit {
    val claimed = AtomicBoolean(false)
    return { if (claimed.compareAndSet(false, true)) stop() }
}

/** Runs [stop] on SIGINT, SIGTERM or `exitProcess`, giving up after [timeout]; failures go to [onFailure]. */
internal fun addStopHook(
    addShutdownHook: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook,
    onFailure: (Throwable) -> Unit = ::printStopFailure,
    timeout: Duration = STOP_TIMEOUT,
    stop: suspend () -> Unit,
) = addShutdownHook(shutdownHook(timeout, onFailure, stop))

/**
 * Feeds [collect] with [server]'s sessions until cancelled; reports through [onStatus] a stop by someone else, a [stop] past
 * [stopTimeout] and any failure, foreign [CancellationException]s included. Rethrows a [VirtualMachineError].
 */
internal suspend fun runServer(
    server: AppMonitorServer,
    collect: suspend (Flow<DeviceSession>) -> Unit,
    start: suspend () -> Int = server::start,
    stop: suspend () -> Unit = server::stop,
    stopTimeout: Duration = STOP_TIMEOUT,
    onStatus: (String) -> Unit,
) {
    try {
        val port = start()
        onStatus("Listening on port $port")
        collect(server.sessions)
        onStatus("Server stopped")
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        onStatus("Server failed: ${e.message ?: e::class.simpleName}")
    } catch (e: Throwable) {
        onStatus("Server failed: ${e.message ?: e::class.simpleName}")
        if (e is VirtualMachineError) throw e
    } finally {
        withContext(NonCancellable) {
            try {
                withTimeout(stopTimeout) { stop() }
            } catch (e: TimeoutCancellationException) {
                onStatus("Server stop timed out after $stopTimeout")
            } catch (e: Throwable) {
                onStatus("Server stop failed: ${e.message ?: e::class.simpleName}")
                if (e is VirtualMachineError) throw e
            }
        }
    }
}
