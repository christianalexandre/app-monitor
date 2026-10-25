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
import kotlin.coroutines.cancellation.CancellationException

/** ADR-0110 C.1: the rule's answer after its delay, as a call built without the engine; a failure is an [IOException]. */
@OptIn(InternalAPI::class) // DEBT-0083
internal suspend fun answer(
    client: HttpClient,
    sink: CaptureSink,
    request: HttpRequestBuilder,
    content: OutgoingContent,
    rule: MockRule,
    id: String,
    masking: Masking,
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
    val requestData =
        HttpRequestBuilder()
            .takeFromWithExecutionContext(request)
            .apply { body = content }
            .build()
    val headers = Headers.build { for (header in mocked.headers) append(header.name, header.value_) }
    val responseData =
        HttpResponseData(
            statusCode = HttpStatusCode.fromValue(mocked.status_code),
            requestTime = GMTDate(),
            headers = headers,
            version = HttpProtocolVersion.HTTP_1_1,
            body = ByteReadChannel(mocked.body.toByteArray()),
            callContext = client.engine.dispatcher + Job(requestData.executionContext),
        )
    val call = HttpClientCall(client, requestData, responseData)
    val body = CapturedBody(mocked.body, truncated = false, size = mocked.body.size.toLong())
    sink.emit("ResponseReceived") { responseReceived(id, call.response, masking, body) }
    return call
}
