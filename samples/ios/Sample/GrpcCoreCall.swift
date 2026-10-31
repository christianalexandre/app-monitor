import Foundation

#if SAMPLE_GRPC
/// One unary gRPC-Core call over TLS: `grpc_proxy` sends it through the desktop's proxy and
/// `GRPC_DEFAULT_SSL_ROOTS_FILE_PATH` makes its CA trusted (ADR-0108). `-grpcTarget host:port`
/// at launch replaces grpcb.in, as `make e2e-ios` does.
enum GrpcCoreCall {
    static let method = "/grpcbin.GRPCBin/DummyUnary"
    /// A `grpcbin.DummyMessage` whose `f_string` (field 1) is "hello from the sample".
    static let message: [UInt8] = [0x0A, 21] + Array("hello from the sample".utf8)

    private static let target = UserDefaults.standard.string(forKey: "grpcTarget") ?? "grpcb.in:9001"
    private static let initialized: Void = grpc_init()

    static func run() async -> String {
        await Task.detached { unary() }.value
    }

    private static func unary() -> String {
        _ = initialized
        guard let credentials = grpc_ssl_credentials_create(nil, nil, nil, nil) else { return "no credentials" }
        defer { grpc_channel_credentials_release(credentials) }
        guard let channel = grpc_channel_create(target, credentials, nil) else { return "no channel" }
        defer { grpc_channel_destroy(channel) }
        let queue = grpc_completion_queue_create_for_pluck(nil)
        defer { grpc_completion_queue_destroy(queue) }
        let path = grpc_slice_from_copied_string(method)
        defer { grpc_slice_unref(path) }
        let deadline = gpr_time_add(gpr_now(GPR_CLOCK_REALTIME), gpr_time_from_seconds(10, GPR_TIMESPAN))
        guard let call = grpc_channel_create_call(channel, nil, 0, queue, path, nil, deadline, nil) else {
            return "no call"
        }
        defer { grpc_call_unref(call) }

        var slice = message.withUnsafeBufferPointer { grpc_slice_from_copied_buffer($0.baseAddress, $0.count) }
        let request = grpc_raw_byte_buffer_create(&slice, 1)
        grpc_slice_unref(slice)
        defer { grpc_byte_buffer_destroy(request) }
        var initial = grpc_metadata_array()
        var trailing = grpc_metadata_array()
        grpc_metadata_array_init(&initial)
        grpc_metadata_array_init(&trailing)
        defer {
            grpc_metadata_array_destroy(&initial)
            grpc_metadata_array_destroy(&trailing)
        }
        var response: UnsafeMutablePointer<grpc_byte_buffer>?
        var status = GRPC_STATUS_UNKNOWN
        var details = grpc_empty_slice()
        defer { grpc_slice_unref(details) }

        var ops = [grpc_op](repeating: grpc_op(), count: 6)
        ops[0].op = GRPC_OP_SEND_INITIAL_METADATA
        ops[1].op = GRPC_OP_SEND_MESSAGE
        ops[1].data.send_message.send_message = request
        ops[2].op = GRPC_OP_SEND_CLOSE_FROM_CLIENT
        ops[3].op = GRPC_OP_RECV_INITIAL_METADATA
        ops[4].op = GRPC_OP_RECV_MESSAGE
        ops[5].op = GRPC_OP_RECV_STATUS_ON_CLIENT
        let started = withUnsafeMutablePointer(to: &initial) { initialPointer in
            withUnsafeMutablePointer(to: &response) { responsePointer in
                withUnsafeMutablePointer(to: &trailing) { trailingPointer in
                    withUnsafeMutablePointer(to: &status) { statusPointer in
                        withUnsafeMutablePointer(to: &details) { detailsPointer in
                            ops[3].data.recv_initial_metadata.recv_initial_metadata = initialPointer
                            ops[4].data.recv_message.recv_message = responsePointer
                            ops[5].data.recv_status_on_client.trailing_metadata = trailingPointer
                            ops[5].data.recv_status_on_client.status = statusPointer
                            ops[5].data.recv_status_on_client.status_details = detailsPointer
                            let tag = UnsafeMutableRawPointer(bitPattern: 1)
                            guard grpc_call_start_batch(call, ops, ops.count, tag, nil) == GRPC_CALL_OK else {
                                return false
                            }
                            let event = grpc_completion_queue_pluck(queue, tag, gpr_inf_future(GPR_CLOCK_REALTIME), nil)
                            return event.success != 0
                        }
                    }
                }
            }
        }
        if let response { grpc_byte_buffer_destroy(response) }
        guard started else { return "gRPC batch failed" }
        if status != GRPC_STATUS_OK {
            let text =
                grpc_slice_to_c_string(details).map { text in
                    defer { gpr_free(text) }
                    return String(cString: text)
                } ?? ""
            return "gRPC status \(status.rawValue) \(text)"
        }
        return "gRPC status 0, \(response == nil ? "no" : "one") message"
    }
}
#endif
