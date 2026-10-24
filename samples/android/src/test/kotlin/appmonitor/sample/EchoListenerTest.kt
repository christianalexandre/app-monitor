package appmonitor.sample

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

class EchoListenerTest {
    @Test
    fun failureAfterClosingDoesNotResumeTwice() {
        val results = mutableListOf<String>()
        val body: suspend () -> String = {
            suspendCoroutine { continuation ->
                val listener = EchoListener("hi") { continuation.resume(it) }
                listener.onClosing(StubWebSocket, 1000, "done")
                listener.onFailure(StubWebSocket, IOException("reset"), null)
            }
        }
        body.startCoroutine(
            Continuation(EmptyCoroutineContext) { results += it.getOrThrow() },
        )
        assertEquals(listOf("WebSocket closed 1000"), results)
    }

    @Test
    fun failureAloneIsReported() {
        val results = mutableListOf<String>()
        val listener = EchoListener("hi") { results += it }
        listener.onFailure(StubWebSocket, IOException("reset"), null)
        listener.onFailure(StubWebSocket, IOException("again"), null)
        assertEquals(listOf("WebSocket failed: java.io.IOException: reset"), results)
    }
}

private object StubWebSocket : WebSocket {
    override fun request(): Request = Request.Builder().url("https://example.invalid").build()

    override fun queueSize(): Long = 0

    override fun send(text: String): Boolean = true

    override fun send(bytes: ByteString): Boolean = true

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean = true

    override fun cancel() = Unit
}
