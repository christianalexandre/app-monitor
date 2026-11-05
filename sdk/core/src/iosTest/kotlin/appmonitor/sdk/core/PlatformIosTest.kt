package appmonitor.sdk.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformIosTest {
    @Test
    fun aLogLineWithFormatSpecifiersIsWritten() {
        platformLog("100% %@ %s, on a dispatcher too")
        runBlocking(Dispatchers.Default) { platformLog("100% %@ %s") }
    }

    @Test
    fun aConnectFailureNamesTheInfoPlistKeys() {
        assertTrue("NSLocalNetworkUsageDescription" in CONNECT_FAILURE_HINT)
        assertTrue("NSBonjourServices" in CONNECT_FAILURE_HINT)
        assertTrue("ACCESS_LOCAL_NETWORK" !in CONNECT_FAILURE_HINT)
    }
}
