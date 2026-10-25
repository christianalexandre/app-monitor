package appmonitor.desktop.data

import appmonitor.desktop.domain.Breakpoint
import appmonitor.desktop.domain.BreakpointChange
import appmonitor.desktop.domain.BreakpointWrite
import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.ConnectionBytes
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Mock
import appmonitor.desktop.domain.MockChange
import appmonitor.desktop.domain.MockSource
import appmonitor.desktop.domain.MockWrite
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.StatusClass
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.storage.ExchangeDetail
import appmonitor.desktop.storage.StorageHealth
import appmonitor.desktop.storage.StoredBreakpoint
import appmonitor.desktop.storage.StoredExchange
import appmonitor.desktop.storage.StoredFrame
import appmonitor.desktop.storage.StoredMock
import appmonitor.desktop.storage.StoredRun
import appmonitor.desktop.storage.StoredSession
import appmonitor.desktop.storage.BreakpointChange as StoredBreakpointChange
import appmonitor.desktop.storage.BreakpointWrite as StoredBreakpointWrite
import appmonitor.desktop.storage.ExchangeFilter as StoredExchangeFilter
import appmonitor.desktop.storage.MockChange as StoredMockChange
import appmonitor.desktop.storage.MockSource as StoredMockSource
import appmonitor.desktop.storage.MockWrite as StoredMockWrite
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

internal fun StoredMock.toDomain(): Mock =
    Mock(id, appId, position, name, enabled, pending, source.toDomain(), rule, createdAtMs, updatedAtMs)

internal fun StoredMockSource.toDomain(): MockSource = MockSource.valueOf(name)

internal fun MockSource.toStored(): StoredMockSource = StoredMockSource.valueOf(name)

internal fun MockChange.toStored(): StoredMockChange = StoredMockChange(name, enabled, rule)

internal fun StoredMockWrite.toDomain(): MockWrite =
    when (this) {
        is StoredMockWrite.Saved -> MockWrite.Saved(id)
        StoredMockWrite.UnknownMock -> MockWrite.UnknownMock
        StoredMockWrite.NotSaved -> MockWrite.NotSaved
    }

internal fun StoredBreakpoint.toDomain(): Breakpoint = Breakpoint(id, appId, position, name, enabled, rule, createdAtMs, updatedAtMs)

internal fun BreakpointChange.toStored(): StoredBreakpointChange = StoredBreakpointChange(name, enabled, rule)

internal fun StoredBreakpointWrite.toDomain(): BreakpointWrite =
    when (this) {
        is StoredBreakpointWrite.Saved -> BreakpointWrite.Saved(id)
        StoredBreakpointWrite.UnknownBreakpoint -> BreakpointWrite.UnknownBreakpoint
        StoredBreakpointWrite.NotSaved -> BreakpointWrite.NotSaved
    }
