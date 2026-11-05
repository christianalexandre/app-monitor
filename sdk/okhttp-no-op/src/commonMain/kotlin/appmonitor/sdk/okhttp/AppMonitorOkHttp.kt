package appmonitor.sdk.okhttp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The release-build entry points (ADR-0112, ADR-0159): add nothing. */
public object AppMonitorOkHttp {
    /** Does nothing. */
    @JvmStatic
    public fun install(builder: OkHttpClient.Builder) {
    }

    /** Null: OkHttp opens the socket as usual. */
    @JvmStatic
    public fun newWebSocket(
        client: OkHttpClient,
        request: Request,
        listener: WebSocketListener,
    ): WebSocket? = null
}
