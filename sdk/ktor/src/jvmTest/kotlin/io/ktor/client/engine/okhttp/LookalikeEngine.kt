package io.ktor.client.engine.okhttp

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk

/** An engine in OkHttp's package that never reaches OkHttp: how a name test sees Ktor's after R8 renames it. */
class LookalikeEngine(
    delegate: HttpClientEngine = MockEngine { respondOk() },
) : HttpClientEngine by delegate
