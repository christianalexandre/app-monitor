package appmonitor.sdk.ktor

import io.ktor.client.engine.darwin.Darwin

class DarwinWebSocketCaptureTest : WebSocketCaptureTest(Darwin, losesClose = true)
