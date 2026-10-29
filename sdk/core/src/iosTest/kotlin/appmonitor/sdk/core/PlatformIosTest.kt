package appmonitor.sdk.core

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformIosTest {
    @Test
    fun aConnectFailureNamesTheInfoPlistKeys() {
        assertTrue("NSLocalNetworkUsageDescription" in CONNECT_FAILURE_HINT)
        assertTrue("NSBonjourServices" in CONNECT_FAILURE_HINT)
        assertTrue("ACCESS_LOCAL_NETWORK" !in CONNECT_FAILURE_HINT)
    }
}
