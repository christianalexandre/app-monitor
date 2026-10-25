package appmonitor.desktop.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

class McpPortTest {
    @Test
    fun portComesFromTheVariableWhenItIsAPortNumber() {
        fun port(value: String?) = mcpPort { if (it == "APPMONITOR_MCP_PORT") value else null }
        assertEquals(47475, port(null))
        assertEquals(48000, port("48000"))
        assertEquals(48000, port(" 48000 "))
        for (bad in listOf("", "abc", "0", "65536", "-1")) assertEquals(DEFAULT_MCP_PORT, port(bad), bad)
    }
}
