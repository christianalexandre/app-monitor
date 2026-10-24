package appmonitor.sdk.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosDiscoveryTest {
    @Test
    fun simulatorHostFollowsABonjourThatFindsNothing() =
        runTest {
            var closes = 0
            val silent = MdnsBrowser { AutoCloseable { closes++ } }
            assertTrue(isSimulator)
            val discover = discovery(AppMonitorOptions(), { silent }, SIMULATOR_HOST.takeIf { isSimulator })

            assertEquals(Endpoint("127.0.0.1"), discover())
            assertEquals(3_000, testScheduler.currentTime)
            assertEquals(1, closes)
        }
}
