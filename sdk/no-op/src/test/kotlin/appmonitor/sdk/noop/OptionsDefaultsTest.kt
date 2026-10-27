package appmonitor.sdk.noop

import appmonitor.protocol.Protocol
import appmonitor.sdk.core.AppMonitorOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ABI dumps do not hold default values; these do (ADR-0072). */
class OptionsDefaultsTest {
    @Test
    fun `defaults and limits match the real SDK`() {
        val options = AppMonitorOptions()
        assertEquals(Protocol.DEFAULT_PORT, options.port)
        assertEquals(Protocol.MAX_BODY_BYTES, options.maxBodyBytes)
        assertEquals(true, options.enabled)
        assertEquals(null, options.host)
        assertEquals(true, options.masking)
        assertFailsWith<IllegalArgumentException> { AppMonitorOptions(maxBodyBytes = Protocol.MAX_BODY_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { AppMonitorOptions(port = 0) }
    }
}
