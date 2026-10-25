package appmonitor.desktop.mcp

import appmonitor.desktop.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.Base64

/** The bearer token every MCP request must carry (ADR-0090 B.2), kept in the store's `settings`. */
class McpAccess private constructor(
    private val storage: Storage,
    token: String,
) {
    private val mutableToken = MutableStateFlow(token)

    /** Read on each request: a new value takes effect without a restart. */
    val token: StateFlow<String> = mutableToken.asStateFlow()

    /** Replaces the token, in memory at once and in the store through its queue; returns the new one. */
    suspend fun regenerate(): String {
        val token = newToken()
        mutableToken.value = token
        storage.setSetting(KEY_TOKEN, token)
        return token
    }

    companion object {
        const val KEY_TOKEN = "mcp.token"
        private const val TOKEN_BYTES = 32
        private val random = SecureRandom()

        /** The stored token, or a new one written to the store when there is none. */
        suspend fun open(storage: Storage): McpAccess {
            storage.setting(KEY_TOKEN)?.let { return McpAccess(storage, it) }
            val token = newToken()
            storage.setSetting(KEY_TOKEN, token)
            return McpAccess(storage, token)
        }

        private fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
