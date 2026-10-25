package appmonitor.desktop.mcp

import appmonitor.desktop.data.settingRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessTest {
    @Test
    fun loopbackNamesWithTheTokenPass() =
        withServer { running ->
            assertEquals(200, running.post().status)
            assertEquals(200, running.post(headers = mapOf("Host" to "localhost:${running.port}")).status)
            assertEquals(200, running.post(headers = mapOf("Origin" to "http://localhost:${running.port}")).status)
            assertEquals(200, running.post(headers = mapOf("Origin" to "http://127.0.0.1:${running.port}")).status)
        }

    @Test
    fun foreignOriginIsRefused() =
        withServer { running ->
            for (origin in listOf("http://evil.example", "http://127.0.0.1:1", "https://localhost:${running.port}", "null")) {
                assertEquals(403, running.post(headers = mapOf("Origin" to origin)).status, origin)
            }
        }

    @Test
    fun foreignHostIsRefused() =
        withServer { running ->
            for (host in listOf("evil.example:${running.port}", "[::1]:${running.port}", "127.0.0.1")) {
                assertEquals(403, running.post(headers = mapOf("Host" to host)).status, host)
            }
        }

    @Test
    fun wrongPortInHostIsRefused() =
        withServer { running ->
            assertEquals(403, running.post(headers = mapOf("Host" to "127.0.0.1:${running.port + 1}")).status)
            assertEquals(403, running.post(headers = mapOf("Host" to "localhost:$DEFAULT_MCP_PORT")).status)
        }

    @Test
    fun missingTokenIsRefused() =
        withServer { running ->
            val response = running.post(headers = mapOf("Authorization" to null))
            assertEquals(401, response.status)
            assertEquals("Bearer", response.headers["www-authenticate"])
        }

    @Test
    fun wrongTokenIsRefused() =
        withServer { running ->
            val token = running.access.token.value
            for (value in listOf("Bearer x$token", "Bearer ${token.dropLast(1)}", token, "Basic $token")) {
                assertEquals(401, running.post(headers = mapOf("Authorization" to value)).status, value)
            }
        }

    @Test
    fun regeneratedTokenTakesEffectAtOnce() =
        withServer { running ->
            val old = running.access.token.value
            val new = running.access.regenerate()
            assertEquals(401, running.post(headers = mapOf("Authorization" to "Bearer $old")).status)
            assertEquals(200, running.post(headers = mapOf("Authorization" to "Bearer $new")).status)
            running.storage.flush()
            assertEquals(new, running.storage.setting(McpAccess.KEY_TOKEN), "kept for the next launch")
        }

    @Test
    fun tokenIsKeptAcrossLaunchesAndMadeWhenMissing() =
        withStorage { storage ->
            val first = McpAccess.open(storage.settingRepository()).token.value
            assertEquals(43, first.length, "32 bytes, base64url without padding")
            storage.flush()
            assertEquals(first, McpAccess.open(storage.settingRepository()).token.value)
            storage.clearAll()
            val after = McpAccess.open(storage.settingRepository()).token.value
            assert(after != first) { "clearAll deletes the token" }
        }
}
