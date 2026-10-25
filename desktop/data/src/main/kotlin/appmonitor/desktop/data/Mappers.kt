package appmonitor.desktop.data

import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.ConnectionBytes
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StatusClass
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.storage.ExchangeDetail
import appmonitor.desktop.storage.StorageHealth
import appmonitor.desktop.storage.StoredExchange
import appmonitor.desktop.storage.StoredFrame
import appmonitor.desktop.storage.StoredRun
import appmonitor.desktop.storage.StoredSession
import appmonitor.desktop.storage.ExchangeFilter as StoredExchangeFilter
import appmonitor.desktop.storage.StatusClass as StoredStatusClass

fun StoredRun.toDomain(): Run = Run(id, name, startedAtMs, endedAtMs)

internal fun StoredSession.toDomain(): Session =
    Session(id, runId, serverSessionId, hello, peerAddress, connectedAtMs, disconnectedAtMs, lastEnvelopeAtMs)

fun StoredExchange.toDomain(): Exchange =
    Exchange(
        id = id,
        sessionId = sessionId,
        requestId = requestId,
        method = method,
        url = url,
        host = host,
        statusCode = statusCode,
        failed = failed,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        captureLayer = captureLayer,
        error = error,
        frameCount = frameCount,
        grpcStatus = grpcStatus,
        mockRuleId = mockRuleId,
        replayOf = replayOf,
    )

internal fun StorageHealth.toDomain(): StoreHealth = StoreHealth(notSaved, lastError)

fun ExchangeFilter.toStored(): StoredExchangeFilter =
    StoredExchangeFilter(
        text = text,
        method = method,
        statusClass = statusClass?.toStored(),
        failedOnly = failedOnly,
        deviceId = deviceId,
        appId = appId,
        runId = runId,
        sessionId = sessionId,
    )

internal fun StatusClass.toStored(): StoredStatusClass = StoredStatusClass.valueOf(name)

internal fun ExchangeDetail.toDomain(frames: List<StoredFrame>): HttpExchange {
    val row = exchange
    return HttpExchange(
        id = row.id,
        requestId = row.requestId,
        method = row.method,
        url = row.url,
        requestHeaders = request?.headers.orEmpty(),
        requestBody = request?.let { CapturedBody(it.body, it.body_truncated, it.body_size) } ?: CapturedBody(),
        startedAtMs = row.startedAtMs,
        statusCode = row.statusCode,
        grpcStatus = row.grpcStatus,
        responseHeaders = response?.headers.orEmpty(),
        responseBody = response?.let { CapturedBody(it.body, it.body_truncated, it.body_size) } ?: CapturedBody(),
        error = row.error,
        endedAtMs = row.endedAtMs,
        frames =
            frames.map {
                CapturedFrame(
                    it.direction,
                    it.opcode,
                    CapturedBody(it.payload, it.payloadTruncated, it.payloadSize),
                    it.atMs,
                )
            },
        frameCount = row.frameCount,
        captureLayer = row.captureLayer,
        connection = connectionBytes?.let { ConnectionBytes(it.sent, it.received) },
        mockRuleId = row.mockRuleId,
        mockRuleName = mockRuleName,
        replayOf = row.replayOf,
        replayOfId = replayOfId,
    )
}
