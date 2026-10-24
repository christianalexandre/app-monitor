package appmonitor.desktop.storage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ApprovalStoreTest {
    @Test
    fun storedReopenedForgotten() =
        withDataDir { dir ->
            var now = 1_000L
            val first = Storage.open(dir) { now }
            runBlocking {
                first.decided(hello("device-1"), approved = false)
                now = 2_000
                first.decided(hello("device-1").copy(device_name = "Pixel 10", app_version = "1.1"), approved = true)
                first.decided(hello("device-2"), approved = false)
                first.close(5.seconds)
            }
            val second = Storage.open(dir) { now }
            runBlocking {
                assertEquals(
                    listOf(
                        StoredApproval("device-1", "dev.appmonitor.sample", true, 2_000, "Pixel 10", "16", "1.1"),
                        StoredApproval("device-2", "dev.appmonitor.sample", false, 2_000, "Pixel 9", "16", "1.0"),
                    ),
                    second.approvals(),
                )
                second.forget("device-1", "dev.appmonitor.sample")
                second.flush()
                assertEquals(listOf("device-2"), second.approvals().map { it.deviceId })
                second.close(5.seconds)
            }
            val third = Storage.open(dir) { now }
            runBlocking {
                assertEquals(listOf("device-2"), third.approvals().map { it.deviceId })
                third.forgetAll()
                third.close(5.seconds)
            }
            val fourth = Storage.open(dir) { now }
            runBlocking {
                assertEquals(emptyList(), fourth.approvals())
                fourth.close(5.seconds)
            }
        }

    @Test
    fun observedApprovalsFollowCommits() =
        withDataDir { dir ->
            val storage = Storage.open(dir)
            runBlocking {
                withTimeout(5.seconds) {
                    val approvals = storage.observeApprovals()
                    assertEquals(emptyList(), approvals.first())
                    storage.decided(hello(), approved = true)
                    approvals.first { it.isNotEmpty() }
                    storage.forgetAll()
                    approvals.first { it.isEmpty() }
                }
                storage.close(5.seconds)
            }
        }
}
