package appmonitor.sdk.okhttp

import appmonitor.protocol.Protocol.CAPTURE_HEADER
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The entry points the Gradle plugin calls from `OkHttpClient` in debug builds (ADR-0112, ADR-0127, ADR-0159). */
public object AppMonitorOkHttp {
    /** Adds an [AppMonitorInterceptor] to [builder] unless it already has one, as an application or network interceptor. */
    @JvmStatic
    public fun install(builder: OkHttpClient.Builder) {
        val present = (builder.interceptors() + builder.networkInterceptors()).any { it is AppMonitorInterceptor }
        if (!present) builder.addInterceptor(AppMonitorInterceptor())
    }

    /**
     * Called first in `OkHttpClient.newWebSocket`: a socket whose frames are captured, or null to let OkHttp open
     * it as usual. Null while the monitor is idle, for the factory's own call (its request is tagged), and for a
     * request the Ktor plugin captures (ADR-0129).
     */
    @JvmStatic
    public fun newWebSocket(
        client: OkHttpClient,
        request: Request,
        listener: WebSocketListener,
    ): WebSocket? = newWebSocket(client, request, listener, MonitorSink)

    internal fun newWebSocket(
        client: OkHttpClient,
        request: Request,
        listener: WebSocketListener,
        sink: CaptureSink,
    ): WebSocket? {
        if (sink.options == null || request.tag(WebSocketCapture::class.java) != null) return null
        if (request.header(CAPTURE_HEADER) != null) return null
        return AppMonitorWebSocketFactory(client, sink).newWebSocket(request, listener)
    }
}
