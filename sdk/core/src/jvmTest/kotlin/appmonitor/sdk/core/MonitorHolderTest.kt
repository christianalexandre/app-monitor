package appmonitor.sdk.core

import appmonitor.v1.Hello
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class MonitorHolderTest {
    private val created = mutableListOf<MonitorClient>()

    private fun newClient(options: AppMonitorOptions) = MonitorClient({ Hello() }, discover = { null }).also { created += it }

    @Test
    fun disabledStaysIdle() {
        val holder = MonitorHolder()

        holder.start(AppMonitorOptions(enabled = false), ::newClient)

        assertNull(holder.client)
        assertEquals(0, created.size)
    }

    @Test
    fun aSecondStartIsIgnoredWhileRunning() =
        runBlocking {
            val holder = MonitorHolder()

            holder.start(AppMonitorOptions(host = "first"), ::newClient)
            holder.start(AppMonitorOptions(host = "second"), ::newClient)

            assertEquals(1, created.size)
            assertSame(created.single(), holder.client)
            holder.stop()
            created.single().join()
        }

    @Test
    fun startAfterStopBuildsANewClient() =
        runBlocking {
            val holder = MonitorHolder()

            holder.start(AppMonitorOptions(), ::newClient)
            holder.stop()
            created.first().join()
            holder.start(AppMonitorOptions(), ::newClient)

            assertEquals(ClientState.Stopped, created.first().state.value)
            assertNotSame(created.first(), holder.client)
            assertEquals(2, created.size)
            holder.stop()
            created.last().join()
        }

    @Test
    fun stopWhenIdleDoesNothing() {
        MonitorHolder().stop()
    }
}
