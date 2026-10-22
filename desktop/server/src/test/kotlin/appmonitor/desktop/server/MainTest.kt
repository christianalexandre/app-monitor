package appmonitor.desktop.server

import appmonitor.protocol.Protocol
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `no arguments use the default port`() {
        assertEquals(Protocol.DEFAULT_PORT, parse().first)
    }

    @Test
    fun `a valid port is used`() {
        assertEquals(8080, parse("--port", "8080").first)
    }

    @Test
    fun `a bad port is a one-line error`() {
        listOf(arrayOf("--port", "abc"), arrayOf("--port"), arrayOf("--port", "70000"), arrayOf("--prot", "1")).forEach { args ->
            val (port, err) = parse(*args)

            assertNull(port, args.joinToString(" "))
            assertEquals(1, err.trimEnd().lines().size, err)
            assertTrue(err.startsWith("Usage: server"), err)
        }
    }

    private fun parse(vararg args: String): Pair<Int?, String> {
        val err = ByteArrayOutputStream()
        val port = parsePort(arrayOf(*args), PrintStream(err))
        return port to err.toString()
    }
}
