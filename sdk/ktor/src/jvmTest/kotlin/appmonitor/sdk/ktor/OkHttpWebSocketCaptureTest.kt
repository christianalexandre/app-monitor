package appmonitor.sdk.ktor

import io.ktor.client.engine.okhttp.OkHttp

class OkHttpWebSocketCaptureTest : WebSocketCaptureTest(OkHttp, answersCloseItself = true)
