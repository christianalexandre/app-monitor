package appmonitor.desktop.app

import appmonitor.desktop.app.store.ServiceSwitch
import appmonitor.desktop.domain.ServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.BindException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val PORT = 7

class ServiceSwitchTest {
    private var stored: String? = null
    private val events = mutableListOf<String>()
    private var startService: suspend () -> Int = { PORT }
    private var readStored: suspend () -> String? = { stored }
    private var writeStored: suspend (String) -> Unit = { stored = it }

    private fun switch(onByDefault: Boolean = true) =
        ServiceSwitch(
            onByDefault = onByDefault,
            port = PORT,
            readStored = { readStored() },
            writeStored = { writeStored(it) },
            startService = {
                events += "start"
                startService()
            },
            stopService = { events += "stop" },
        )

    @Test
    fun `the default applies until a value is stored`() =
        runTest {
            val on = switch(onByDefault = true)
            on.start()
            assertEquals(ServiceStatus.Running(PORT), on.status.value)

            val off = switch(onByDefault = false)
            off.start()
            assertEquals(ServiceStatus.Off, off.status.value)

            stored = "false"
            val storedOff = switch(onByDefault = true)
            storedOff.start()
            assertEquals(false, storedOff.enabled.value)
            assertEquals(ServiceStatus.Off, storedOff.status.value)
        }

    @Test
    fun `a switch during start's read wins over what it read`() =
        runTest {
            val reading = CompletableDeferred<Unit>()
            val read = CompletableDeferred<String?>()
            readStored = {
                reading.complete(Unit)
                read.await()
            }
            val switch = switch()

            val start = launch { switch.start() }
            reading.await()
            switch.set(false)
            read.complete("true")
            start.join()

            assertEquals(false, switch.enabled.value)
            assertEquals(ServiceStatus.Off, switch.status.value)
            assertEquals("false", stored)
            assertEquals(emptyList(), events)
        }

    @Test
    fun `a switch off while the service starts ends it off`() =
        runTest {
            val starting = CompletableDeferred<Unit>()
            val bound = CompletableDeferred<Int>()
            startService = {
                starting.complete(Unit)
                bound.await()
            }
            val switch = switch()

            val start = launch { switch.start() }
            starting.await()
            val off = launch { switch.set(false) }
            bound.complete(PORT)
            start.join()
            off.join()

            assertEquals(false, switch.enabled.value)
            assertEquals(ServiceStatus.Off, switch.status.value)
            assertEquals(listOf("start", "stop"), events)
        }

    @Test
    fun `two switches at once leave the store and the service on the last value`() =
        runBlocking {
            val firstWriting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            writeStored = { value ->
                if (firstWriting.complete(Unit)) release.await()
                stored = value
            }
            val switch = switch()

            val on = launch(start = CoroutineStart.UNDISPATCHED) { switch.set(true) }
            firstWriting.await()
            val off = launch(start = CoroutineStart.UNDISPATCHED) { switch.set(false) }
            release.complete(Unit)
            on.join()
            off.join()

            assertEquals(false, switch.enabled.value)
            assertEquals("false", stored)
            assertEquals(ServiceStatus.Off, switch.status.value)
        }

    @Test
    fun `a taken port and a failure are statuses, and the next switch tries again`() =
        runTest {
            val switch = switch()
            startService = { throw BindException("taken") }
            switch.start()
            assertEquals(ServiceStatus.PortTaken(PORT), switch.status.value)

            startService = { throw IOException("no disk") }
            switch.set(true)
            assertEquals(ServiceStatus.Failed("no disk"), switch.status.value)

            startService = { PORT }
            switch.set(true)
            assertEquals(ServiceStatus.Running(PORT), switch.status.value)
        }

    @Test
    fun `a start cancelled is off and stays cancelled`() =
        runTest {
            startService = { throw CancellationException("window closed") }
            val switch = switch()

            assertFailsWith<CancellationException> { switch.start() }

            assertEquals(ServiceStatus.Off, switch.status.value)
        }

    @Test
    fun `restart around stops, runs and starts only what was running`() =
        runTest {
            val switch = switch()
            switch.start()

            switch.restartAround { events += "between" }
            assertEquals(listOf("start", "stop", "between", "start"), events)
            assertEquals(ServiceStatus.Running(PORT), switch.status.value)

            switch.set(false)
            events.clear()
            switch.restartAround { events += "between" }
            assertEquals(listOf("between"), events)
            assertEquals(ServiceStatus.Off, switch.status.value)
        }

    @Test
    fun `write back stores only a value the default would not bring back`() =
        runTest {
            val on = switch(onByDefault = true)
            on.writeBack()
            assertEquals(null, stored)

            on.set(false)
            stored = null
            on.writeBack()
            assertEquals("false", stored)
        }

    @Test
    fun `closed stays off`() =
        runTest {
            val switch = switch()
            switch.start()

            switch.close()
            switch.set(true)
            switch.start()

            assertEquals(ServiceStatus.Off, switch.status.value)
            assertEquals(listOf("start", "stop"), events)
        }
}
