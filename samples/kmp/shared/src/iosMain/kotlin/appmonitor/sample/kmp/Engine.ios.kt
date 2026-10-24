package appmonitor.sample.kmp

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual val engine: HttpClientEngineFactory<*> = Darwin
