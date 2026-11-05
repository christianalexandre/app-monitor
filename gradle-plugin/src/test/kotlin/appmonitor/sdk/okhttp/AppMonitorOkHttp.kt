package appmonitor.sdk.okhttp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Stands in for the SDK's entry points: counts its calls; `x-wrap` asks it to open the socket itself. */
object AppMonitorOkHttp {
    @JvmField
    var installs: Int = 0

    @JvmField
    var webSockets: Int = 0

    @JvmStatic
    fun install(builder: OkHttpClient.Builder) {
        installs++
    }

    @JvmStatic
    fun newWebSocket(
        client: OkHttpClient,
        request: Request,
        listener: WebSocketListener,
    ): WebSocket? {
        webSockets++
        if (request.header("x-wrap") == null) return null
        return client.newWebSocket(
            request
                .newBuilder()
                .removeHeader("x-wrap")
                .header("x-inner", "1")
                .build(),
            listener,
        )
    }
}
