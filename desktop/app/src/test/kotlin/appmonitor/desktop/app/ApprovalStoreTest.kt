package appmonitor.desktop.app

import appmonitor.desktop.app.store.ApprovalStore
import appmonitor.desktop.app.store.TrafficStore
import appmonitor.desktop.server.AppMonitorServer
import appmonitor.desktop.server.DeviceKey
import appmonitor.desktop.server.ServerConfig
import appmonitor.desktop.storage.Storage
import appmonitor.tools.fakeclient.Fixtures
import appmonitor.tools.fakeclient.Target
import appmonitor.tools.fakeclient.replay
import appmonitor.v1.Hello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ApprovalStoreTest {
    private val hello = Hello.ADAPTER.decode(Fixtures.hello.payload)
    private val key = DeviceKey(hello.device_id, hello.app_id)

    @Test
    fun `an answer outlives a restart until it is forgotten`() =
        withDataDir { dir ->
            launch(dir) { approvals, port, _ ->
                val replaying = async(Dispatchers.IO) { runCatching { replay(Target(HOST, port)) } }
                approvals.decide(approvals.pending.first { it.isNotEmpty() }.single(), approved = true)
                assertTrue(replaying.await().isSuccess)
            }
            launch(dir) { approvals, port, disconnected ->
                // Nobody decides here: an asked device would time out.
                replay(Target(HOST, port))
                assertEquals(emptyList(), approvals.pending.value)

                approvals.forget(key)
                assertEquals(listOf(key), disconnected)
                val replaying = async(Dispatchers.IO) { runCatching { replay(Target(HOST, port)) } }
                approvals.decide(approvals.pending.first { it.isNotEmpty() }.single(), approved = false)
                assertTrue(
                    replaying
                        .await()
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("VIOLATED_POLICY"),
                )
            }
            launch(dir) { approvals, port, _ ->
                val rejected = runCatching { replay(Target(HOST, port)) }.exceptionOrNull()
                assertTrue(rejected?.message.orEmpty().contains("VIOLATED_POLICY"), "closed at once: $rejected")
                assertEquals(emptyList(), approvals.pending.value)
                assertEquals(listOf(false), approvals.stored.first().map { it.approved })

                approvals.forgetAll()
                assertEquals(emptyList(), approvals.stored.first { it.isEmpty() })
            }
        }

    /** One desktop launch on [dir]: store, answers and a real server, all closed after [block]. */
    private fun launch(
        dir: Path,
        block: suspend CoroutineScope.(ApprovalStore, port: Int, disconnected: List<DeviceKey>) -> Unit,
    ) {
        val storage = Storage.open(dir)
        val disconnected = mutableListOf<DeviceKey>()
        lateinit var server: AppMonitorServer
        runBlocking {
            try {
                val approvals = ApprovalStore.open(storage) { disconnected += it }
                server = AppMonitorServer(ServerConfig(host = HOST, port = 0, advertise = false), approvals.approvals)
                val port = server.start()
                val collecting = launch(Dispatchers.Default) { TrafficStore(storage).collect(server.sessions) }
                try {
                    withTimeout(TIMEOUT_MS * 3) { block(approvals, port, disconnected) }
                } finally {
                    collecting.cancel()
                    server.stop()
                }
            } finally {
                storage.close(5.seconds)
            }
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
