package appmonitor.desktop.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.security.MessageDigest

/**
 * Refuses, before the MCP SDK sees it, a request whose `Host` or `Origin` is not this port on a loopback name (`403`) or
 * that lacks the bearer token (`401`), ADR-0090 A.1 and B.2. The port is the one the connection reached.
 */
internal fun accessCheck(token: () -> String) =
    createApplicationPlugin("McpAccessCheck") {
        onCall { call ->
            val port = call.request.local.localPort
            val hosts =
                call.request.headers
                    .getAll(HttpHeaders.Host)
                    .orEmpty()
            val origins =
                call.request.headers
                    .getAll(HttpHeaders.Origin)
                    .orEmpty()
            val hostOk = hosts.size == 1 && hosts[0] in setOf("127.0.0.1:$port", "localhost:$port")
            val originOk =
                origins.isEmpty() || (origins.size == 1 && origins[0] in setOf("http://127.0.0.1:$port", "http://localhost:$port"))
            if (!hostOk || !originOk) {
                call.respond(HttpStatusCode.Forbidden)
                return@onCall
            }
            val presented =
                call.request.headers[HttpHeaders.Authorization]
                    .orEmpty()
                    .toByteArray()
            if (!MessageDigest.isEqual(presented, "Bearer ${token()}".toByteArray())) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
