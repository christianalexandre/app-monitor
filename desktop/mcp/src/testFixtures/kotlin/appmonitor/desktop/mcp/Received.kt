package appmonitor.desktop.mcp

import com.sun.net.httpserver.HttpServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the local server of [runCurl] received; header names lowercase. */
class Received(
    val method: String,
    val uri: String,
    val headers: List<Pair<String, String>>,
    val body: ByteString,
)

/**
 * Runs [command] with `sh` against a local server, in place of the stored URL's port 1, and returns what it received. The
 * command goes in on stdin: Windows joins a process's arguments into one line and mangles its quotes and line breaks.
 */
fun runCurl(command: String): Received {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    var received: Received? = null
    server.createContext("/") { exchange ->
        received =
            Received(
                exchange.requestMethod,
                exchange.requestURI.toString(),
                exchange.requestHeaders.flatMap { (name, values) -> values.map { name.lowercase() to it } },
                exchange.requestBody.readBytes().toByteString(),
            )
        exchange.sendResponseHeaders(204, -1)
        exchange.close()
    }
    server.start()
    try {
        val process = ProcessBuilder("sh", "-s").redirectErrorStream(true).start()
        process.outputStream.use { it.write(command.replace(":1/", ":${server.address.port}/").toByteArray()) }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "curl did not finish")
        val output = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.exitValue(), output)
    } finally {
        server.stop(0)
    }
    return received ?: error("the server received nothing")
}
