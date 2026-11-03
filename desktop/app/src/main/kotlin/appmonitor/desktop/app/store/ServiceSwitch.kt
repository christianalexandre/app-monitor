package appmonitor.desktop.app.store

import appmonitor.desktop.domain.ServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.BindException

/**
 * A stored switch that starts and stops one service. [startService] returns the bound port and cleans up after itself
 * when it throws: a taken port or any failure is a [status], never a throw. Once [close]d it stays off.
 * A [set] before [start] reads the stored switch wins over it.
 */
internal class ServiceSwitch(
    private val onByDefault: Boolean,
    private val port: Int,
    private val readStored: suspend () -> String?,
    private val writeStored: suspend (String) -> Unit,
    private val startService: suspend () -> Int,
    private val stopService: suspend () -> Unit,
) {
    private val lock = Mutex()
    private var running = false
    private var closed = false
    private val switchLock = Any()
    private var switched = false
    private val mutableStatus = MutableStateFlow<ServiceStatus>(ServiceStatus.Off)
    private val mutableEnabled = MutableStateFlow(onByDefault)

    val status: StateFlow<ServiceStatus> = mutableStatus.asStateFlow()
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** Reads the switch and starts the service when it is on. */
    suspend fun start() {
        val stored = readStored()?.toBooleanStrictOrNull() ?: onByDefault
        lock.withLock {
            val on =
                synchronized(switchLock) {
                    if (!switched) mutableEnabled.value = stored
                    mutableEnabled.value
                }
            if (on) startLocked()
        }
    }

    /** Stores the switch, then starts or stops the service. */
    suspend fun set(on: Boolean) {
        synchronized(switchLock) {
            switched = true
            mutableEnabled.value = on
        }
        // The switch as it is under the lock: the last call to take it stores and applies the last value set.
        lock.withLock {
            val current = mutableEnabled.value
            writeStored(current.toString())
            if (current) startLocked() else stopLocked()
        }
    }

    /** Stops the service, runs [whileStopped], and starts it again if it was running. */
    suspend fun restartAround(whileStopped: suspend () -> Unit) =
        lock.withLock {
            val wasRunning = running
            stopLocked()
            whileStopped()
            if (wasRunning) startLocked()
        }

    /** After every setting was deleted: stores the switch again when it differs from the default. */
    suspend fun writeBack() =
        lock.withLock {
            val current = mutableEnabled.value
            if (current != onByDefault) writeStored(current.toString())
        }

    /** Stops the service for good; call it before the store closes. */
    suspend fun close() =
        withContext(NonCancellable) {
            lock.withLock {
                closed = true
                stopLocked()
            }
        }

    private suspend fun startLocked() {
        if (closed || running) return
        mutableStatus.value = ServiceStatus.Starting
        mutableStatus.value =
            try {
                ServiceStatus.Running(startService()).also { running = true }
            } catch (e: CancellationException) {
                mutableStatus.value = ServiceStatus.Off
                throw e
            } catch (_: BindException) {
                ServiceStatus.PortTaken(port)
            } catch (e: Exception) {
                ServiceStatus.Failed(e.message ?: e::class.simpleName ?: "unknown error")
            }
    }

    private suspend fun stopLocked() {
        if (running) {
            running = false
            withContext(NonCancellable) { stopService() }
        }
        mutableStatus.value = ServiceStatus.Off
    }
}
