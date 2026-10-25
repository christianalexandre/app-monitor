package appmonitor.sdk.ktor

import appmonitor.v1.MockRule
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * ADR-0110 C.1: the rule's answer after its delay, as a call built without the engine, its `ResponseReceived` left
 * to the caller; a failure is an [IOException].
 */
internal suspend fun answer(
    client: HttpClient,
    sink: CaptureSink,
    request: HttpRequestBuilder,
    content: OutgoingContent,
    rule: MockRule,
    id: String,
): HttpClientCall {
    try {
        delay(rule.delay_ms)
        rule.failure?.let { throw IOException(it.message) }
    } catch (e: CancellationException) {
        sink.emit("RequestFailed") { requestFailed(id, e) }
        throw e
    } catch (e: IOException) {
        sink.emit("RequestFailed") { requestFailed(id, e) }
        throw e
    }
    val mocked = checkNotNull(rule.response)
    return builtCall(
        client,
        request,
        content,
        HttpStatusCode.fromValue(mocked.status_code),
        Headers.build { for (header in mocked.headers) append(header.name, header.value_) },
        ByteReadChannel(mocked.body.toByteArray()),
    ) { client.engine.dispatcher + Job(it) }
}

/** A call made of [request] and a response built here, without the engine (DEBT-0083). */
@OptIn(InternalAPI::class)
internal fun builtCall(
    client: HttpClient,
    request: HttpRequestBuilder,
    content: OutgoingContent,
    status: HttpStatusCode,
    headers: Headers,
    body: ByteReadChannel,
    version: HttpProtocolVersion = HttpProtocolVersion.HTTP_1_1,
    requestTime: GMTDate = GMTDate(),
    callContext: (executionContext: Job) -> CoroutineContext,
): HttpClientCall {
    val requestData =
        HttpRequestBuilder()
            .takeFromWithExecutionContext(request)
            .apply { this.body = content }
            .build()
    val responseData = HttpResponseData(status, requestTime, headers, version, body, callContext(requestData.executionContext))
    return HttpClientCall(client, requestData, responseData)
}
