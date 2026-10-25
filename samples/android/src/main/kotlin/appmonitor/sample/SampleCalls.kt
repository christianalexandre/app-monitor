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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val HTTPBIN = "https://httpbin.org"
private const val ECHO = "wss://echo.websocket.org"

/** The calls behind the sample's buttons, against [target] when set, else the public services; blocking, run them off the main thread. */
class SampleCalls(
    target: String? = null,
) {
    private val http = target ?: HTTPBIN
    private val echo = target?.let { "ws${it.removePrefix("http")}/ws" } ?: ECHO
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(AppMonitorInterceptor())
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    private val webSockets = AppMonitorWebSocketFactory(client)

    fun getJson(): String = call(Request.Builder().url("$http/json").build())

    fun postJson(): String =
        call(
            Request
                .Builder()
                .url("$http/post")
                .post("""{"hello":"desktop"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        )

    fun notFound(): String = call(Request.Builder().url("$http/status/404").build())

    /** 418 from the network; a desktop mock rule for the path answers it instead (ADR-0110). */
    fun teapot(): String = call(Request.Builder().url("$http/status/418").build())

    fun timeout(): String =
        call(
            Request.Builder().url("$http/delay/10").build(),
            client.newBuilder().callTimeout(2, TimeUnit.SECONDS).build(),
        )

    suspend fun webSocketEcho(): String =
        suspendCoroutine { continuation ->
            webSockets.newWebSocket(
                Request.Builder().url(echo).build(),
                EchoListener("hello from the sample") { continuation.resume(it) },
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

/** Sends [message], closes on its echo; [done] runs once, though OkHttp may call `onFailure` after `onClosing`. */
internal class EchoListener(
    private val message: String,
    private val done: (String) -> Unit,
) : WebSocketListener() {
    private val finished = AtomicBoolean(false)

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
        finish("WebSocket closed $code")
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        finish("WebSocket failed: $t")
    }

    private fun finish(result: String) {
        if (finished.compareAndSet(false, true)) done(result)
    }
}
