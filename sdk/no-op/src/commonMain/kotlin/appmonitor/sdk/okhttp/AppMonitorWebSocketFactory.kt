package appmonitor.sdk.okhttp

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The release-build WebSocket factory (ADR-0017): hands every call to [delegate] untouched. */
public class AppMonitorWebSocketFactory(
    private val delegate: WebSocket.Factory,
) : WebSocket.Factory {
    override fun newWebSocket(
        request: Request,
        listener: WebSocketListener,
    ): WebSocket = delegate.newWebSocket(request, listener)
}
