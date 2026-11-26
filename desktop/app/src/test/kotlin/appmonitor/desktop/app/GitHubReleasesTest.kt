package appmonitor.desktop.app

import appmonitor.desktop.app.store.GitHubReleases
import appmonitor.desktop.domain.AvailableUpdate
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ADR-0228: one unauthenticated GET; the release's tag and page, or null on anything else.
class GitHubReleasesTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val headers = CopyOnWriteArrayList<Map<String, String>>()
    private var status = 200
    private var body = """{"tag_name":"1.1.0","html_url":"https://github.com/x/y/releases/tag/1.1.0","draft":false}"""

    init {
        server.createContext("/latest") { exchange ->
            headers += exchange.requestHeaders.mapValues { it.value.joinToString() }.mapKeys { it.key.lowercase() }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private val releases = GitHubReleases("http://127.0.0.1:${server.address.port}/latest", version = "1.0.0")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `the latest release is read, with the headers GitHub asks for and nothing else about the machine`() =
        runBlocking {
            assertEquals(AvailableUpdate("1.1.0", "https://github.com/x/y/releases/tag/1.1.0"), releases.latest())
            val sent = headers.single()
            assertEquals("AppMonitor/1.0.0", sent["user-agent"])
            assertEquals("application/vnd.github+json", sent["accept"])
            assertEquals(null, sent["authorization"])
        }

    @Test
    fun `no release, an error page or an unreadable body is null`() =
        runBlocking {
            status = 404
            body = """{"message":"Not Found"}"""
            assertNull(releases.latest())
            status = 200
            body = "<html>"
            assertNull(releases.latest())
            body = """{"html_url":"x"}"""
            assertNull(releases.latest())
        }

    @Test
    fun `an unreachable host is null`() =
        runBlocking {
            server.stop(0)
            assertNull(GitHubReleases("http://127.0.0.1:${server.address.port}/latest").latest())
        }
}
