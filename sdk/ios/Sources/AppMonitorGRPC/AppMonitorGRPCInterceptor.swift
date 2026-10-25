import GRPCCore

/// Captures every call of the `GRPCClient` it is registered on while `AppMonitor` runs (ADR-0099):
/// `GRPCClient(transport: transport, interceptors: [AppMonitorGRPCInterceptor()])`. Never changes what a
/// call sends or receives. Only calls `next` in a release build (ADR-0048).
@available(macOS 15.0, iOS 18.0, watchOS 11.0, tvOS 18.0, visionOS 2.0, *)
public struct AppMonitorGRPCInterceptor: ClientInterceptor {
    public init() {}

    public func intercept<Input: Sendable, Output: Sendable>(
        request: StreamingClientRequest<Input>,
        context: ClientContext,
        next: (
            _ request: StreamingClientRequest<Input>,
            _ context: ClientContext
        ) async throws -> StreamingClientResponse<Output>
    ) async throws -> StreamingClientResponse<Output> {
        #if DEBUG
        try await GRPCCapture.intercept(request: request, context: context, next: next)
        #else
        try await next(request, context)
        #endif
    }
}
