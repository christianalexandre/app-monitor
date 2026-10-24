package appmonitor.sample.kmp

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual val engine: HttpClientEngineFactory<*> = OkHttp
