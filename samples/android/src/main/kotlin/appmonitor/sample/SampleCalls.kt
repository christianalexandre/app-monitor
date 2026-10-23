package appmonitor.sample

import appmonitor.sdk.okhttp.AppMonitorInterceptor
import appmonitor.sdk.okhttp.AppMonitorWebSocketFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val HTTPBIN = "https://httpbin.org"
private const val ECHO = "wss://echo.websocket.org"

/** The calls behind the sample's buttons; blocking, run them off the main thread. */
class SampleCalls {
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(AppMonitorInterceptor())
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    private val webSockets = AppMonitorWebSocketFactory(client)

    fun getJson(): String = call(Request.Builder().url("$HTTPBIN/json").build())

    fun postJson(): String =
        call(
            Request
                .Builder()
                .url("$HTTPBIN/post")
                .post("""{"hello":"desktop"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        )

    fun notFound(): String = call(Request.Builder().url("$HTTPBIN/status/404").build())

    fun timeout(): String =
        call(
            Request.Builder().url("$HTTPBIN/delay/10").build(),
            client.newBuilder().callTimeout(2, TimeUnit.SECONDS).build(),
        )

    suspend fun webSocketEcho(): String =
        suspendCoroutine { continuation ->
            val message = "hello from the sample"
            webSockets.newWebSocket(
                Request.Builder().url(ECHO).build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(message)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text == message) webSocket.close(1000, "done")
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(code, null)
                        continuation.resume("WebSocket closed $code")
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        continuation.resume("WebSocket failed: $t")
                    }
                },
            )
        }

    private fun call(
        request: Request,
        with: OkHttpClient = client,
    ): String =
        try {
            with.newCall(request).execute().use { "${it.code}, ${it.body.string().length} bytes" }
        } catch (e: IOException) {
            "failed: $e"
        }
}
