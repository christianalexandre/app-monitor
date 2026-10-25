package appmonitor.desktop.data

import appmonitor.desktop.domain.Breakpoint
import appmonitor.desktop.domain.BreakpointChange
import appmonitor.desktop.domain.BreakpointWrite
import appmonitor.desktop.domain.CapturedBody
import appmonitor.desktop.domain.CapturedFrame
import appmonitor.desktop.domain.ConnectionBytes
import appmonitor.desktop.domain.Durations
import appmonitor.desktop.domain.Endpoint
import appmonitor.desktop.domain.EndpointChange
import appmonitor.desktop.domain.EndpointDiff
import appmonitor.desktop.domain.EndpointGroups
import appmonitor.desktop.domain.EndpointStats
import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.ExchangeFilter
import appmonitor.desktop.domain.HttpExchange
import appmonitor.desktop.domain.Issue
import appmonitor.desktop.domain.IssueChange
import appmonitor.desktop.domain.IssueSource
import appmonitor.desktop.domain.IssueStatus
import appmonitor.desktop.domain.IssueWrite
import appmonitor.desktop.domain.Mock
import appmonitor.desktop.domain.MockChange
import appmonitor.desktop.domain.MockSource
import appmonitor.desktop.domain.MockWrite
import appmonitor.desktop.domain.Retention
import appmonitor.desktop.domain.RetentionState
import appmonitor.desktop.domain.Run
import appmonitor.desktop.domain.RunSummary
import appmonitor.desktop.domain.Session
import appmonitor.desktop.domain.SessionSummary
import appmonitor.desktop.domain.StatusClass
import appmonitor.desktop.domain.StoreHealth
import appmonitor.desktop.storage.EndpointTally
import appmonitor.desktop.storage.ExchangeDetail
import appmonitor.desktop.storage.RetentionSettings
import appmonitor.desktop.storage.RetentionStatus
import appmonitor.desktop.storage.StorageHealth
import appmonitor.desktop.storage.StoredBreakpoint
import appmonitor.desktop.storage.StoredExchange
import appmonitor.desktop.storage.StoredFrame
import appmonitor.desktop.storage.StoredIssue
import appmonitor.desktop.storage.StoredMock
import appmonitor.desktop.storage.StoredRun
import appmonitor.desktop.storage.StoredSession
import appmonitor.desktop.domain.ExchangeDetail as DomainExchangeDetail
import appmonitor.desktop.storage.BreakpointChange as StoredBreakpointChange
import appmonitor.desktop.storage.BreakpointWrite as StoredBreakpointWrite
import appmonitor.desktop.storage.Durations as StoredDurations
import appmonitor.desktop.storage.Endpoint as StoredEndpoint
import appmonitor.desktop.storage.EndpointChange as StoredEndpointChange
import appmonitor.desktop.storage.EndpointDiff as StoredEndpointDiff
import appmonitor.desktop.storage.EndpointGroups as StoredEndpointGroups
import appmonitor.desktop.storage.ExchangeFilter as StoredExchangeFilter
import appmonitor.desktop.storage.IssueChange as StoredIssueChange
import appmonitor.desktop.storage.IssueSource as StoredIssueSource
import appmonitor.desktop.storage.IssueStatus as StoredIssueStatus
import appmonitor.desktop.storage.IssueWrite as StoredIssueWrite
import appmonitor.desktop.storage.MockChange as StoredMockChange
import appmonitor.desktop.storage.MockSource as StoredMockSource
import appmonitor.desktop.storage.MockWrite as StoredMockWrite
import appmonitor.desktop.storage.RunSummary as StoredRunSummary
import appmonitor.desktop.storage.SessionSummary as StoredSessionSummary
import appmonitor.desktop.storage.StatusClass as StoredStatusClass

internal fun StoredRun.toDomain(): Run = Run(id, name, startedAtMs, endedAtMs)

internal fun StoredSession.toDomain(): Session =
    Session(id, runId, serverSessionId, hello, peerAddress, connectedAtMs, disconnectedAtMs, lastEnvelopeAtMs)

internal fun StoredExchange.toDomain(): Exchange =
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

internal fun ExchangeFilter.toStored(): StoredExchangeFilter =
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
        frames = frames.map { it.toDomain() },
        frameCount = row.frameCount,
        captureLayer = row.captureLayer,
        connection = connectionBytes?.let { ConnectionBytes(it.sent, it.received) },
        mockRuleId = row.mockRuleId,
        mockRuleName = mockRuleName,
        replayOf = row.replayOf,
        replayOfId = replayOfId,
    )
}

internal fun ExchangeDetail.toDomain(): DomainExchangeDetail =
    DomainExchangeDetail(exchange.toDomain(), request, response, mockRuleName, replayOfId)

internal fun StoredFrame.toDomain(): CapturedFrame =
    CapturedFrame(id, direction, opcode, CapturedBody(payload, payloadTruncated, payloadSize), atMs)

internal fun Exchange.toStored(): StoredExchange =
    StoredExchange(
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

internal fun StoredRunSummary.toDomain(): RunSummary = RunSummary(run.toDomain(), exchangeCount, sessions.map { it.toDomain() })

internal fun StoredSessionSummary.toDomain(): SessionSummary =
    SessionSummary(session.toDomain(), deviceId, deviceName, platform, osVersion, appId, appVersion, sdkName)

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

internal fun StoredEndpoint.toDomain(): Endpoint = Endpoint(method, host, path)

internal fun StoredDurations.toDomain(): Durations = Durations(min, p50, p95, p99, max)

internal fun EndpointTally.toDomain(): EndpointStats =
    EndpointStats(
        endpoint = endpoint.toDomain(),
        count = count,
        failed = failed,
        errors = errors,
        requestBytes = requestBytes,
        responseBytes = responseBytes,
        statusClasses = statusClasses,
        durations = durationStats?.toDomain(),
    )

internal fun StoredEndpointGroups.toDomain(): EndpointGroups = EndpointGroups(endpoints.map { it.toDomain() }, exchangesRead, truncated)

internal fun StoredEndpointChange.toDomain(): EndpointChange = EndpointChange(endpoint.toDomain(), base.toDomain(), run.toDomain())

internal fun StoredEndpointDiff.toDomain(): EndpointDiff =
    EndpointDiff(
        added = added.map { it.toDomain() },
        gone = gone.map { it.toDomain() },
        changed = changed.map { it.toDomain() },
        unchanged = unchanged,
        truncated = truncated,
    )

internal fun StoredIssue.toDomain(): Issue =
    Issue(id, title, note, tags, status.toDomain(), source.toDomain(), createdAtMs, updatedAtMs, exchangeCount)

internal fun StoredIssueStatus.toDomain(): IssueStatus = IssueStatus.valueOf(name)

internal fun IssueStatus.toStored(): StoredIssueStatus = StoredIssueStatus.valueOf(name)

internal fun StoredIssueSource.toDomain(): IssueSource = IssueSource.valueOf(name)

internal fun IssueChange.toStored(): StoredIssueChange = StoredIssueChange(title, note, tags, status?.toStored())

internal fun StoredIssueWrite.toDomain(): IssueWrite =
    when (this) {
        is StoredIssueWrite.Saved -> IssueWrite.Saved(id)
        is StoredIssueWrite.UnknownExchanges -> IssueWrite.UnknownExchanges(ids)
        StoredIssueWrite.UnknownIssue -> IssueWrite.UnknownIssue
        StoredIssueWrite.NotSaved -> IssueWrite.NotSaved
    }

internal fun RetentionSettings.toDomain(): Retention = Retention(maxAgeDays, maxBytes)

internal fun Retention.toStored(): RetentionSettings = RetentionSettings(maxAgeDays, maxBytes)

internal fun RetentionStatus.toDomain(): RetentionState = RetentionState(usedBytes, keptOverBound, lastError)
