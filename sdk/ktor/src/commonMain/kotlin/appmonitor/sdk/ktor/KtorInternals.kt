package appmonitor.sdk.ktor

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

// ADR-0110 D.1: the only file that opts in to Ktor's InternalAPI; a Ktor upgrade that breaks one breaks here.

/** A call made of [request] and a response built here, without the engine. */
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

/** The response's body as the engine gave it, or a saved copy, without the receive pipeline. */
@OptIn(InternalAPI::class)
internal val HttpResponse.rawBody: ByteReadChannel get() = rawContent
