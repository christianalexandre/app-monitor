import Foundation

/// The protobuf wire format the generated `Appmonitor_V1_*` types encode with (ADR-0160): varint and
/// length-delimited fields written in field-number order, proto3 defaults left out, unknown fields kept,
/// a message field read twice merged.
protocol ProtoMessage: Hashable, Sendable {
    init()
    var unknownFields: ProtoUnknownFields { get set }
    func encode(to writer: inout ProtoWriter)
    mutating func decodeField(_ number: Int, from reader: inout ProtoReader) throws
}

extension ProtoMessage {
    static func with(_ populator: (inout Self) throws -> Void) rethrows -> Self {
        var message = Self()
        try populator(&message)
        return message
    }

    init(serializedBytes bytes: some ContiguousBytes) throws {
        self.init()
        var reader = bytes.withUnsafeBytes { ProtoReader(bytes: [UInt8]($0)) }
        try decode(from: &reader)
    }

    /// Never throws; `throws` keeps the call sites written for SwiftProtobuf.
    func serializedBytes<Bytes: RangeReplaceableCollection>() throws -> Bytes where Bytes.Element == UInt8 {
        var writer = ProtoWriter()
        encode(to: &writer)
        return Bytes(writer.bytes)
    }

    mutating func decode(from reader: inout ProtoReader) throws {
        while try reader.nextField() {
            try decodeField(reader.fieldNumber, from: &reader)
        }
    }
}

/// The bytes of the fields this schema does not know, as read; written back after the known ones.
struct ProtoUnknownFields: Hashable, Sendable {
    var data = Data()
}

enum ProtoDecodingError: Error, Equatable {
    case truncated
    case malformedVarint
    case wireTypeMismatch(field: Int)
    case invalidUTF8(field: Int)
    case unsupportedWireType(Int)
    case invalidFieldNumber
}

/// Field numbers run from 1 to 2^29 - 1.
private let maxFieldNumber: UInt64 = (1 << 29) - 1

struct ProtoWriter {
    private(set) var bytes: [UInt8] = []

    private mutating func varint(_ value: UInt64) {
        var value = value
        while value >= 0x80 {
            bytes.append(UInt8(truncatingIfNeeded: value) | 0x80)
            value >>= 7
        }
        bytes.append(UInt8(value))
    }

    private mutating func tag(_ number: Int, _ wireType: UInt64) {
        varint(UInt64(number) << 3 | wireType)
    }

    private mutating func delimited(_ number: Int, _ payload: some Collection<UInt8>) {
        tag(number, 2)
        varint(UInt64(payload.count))
        bytes.append(contentsOf: payload)
    }

    mutating func uint32(_ number: Int, _ value: UInt32) {
        tag(number, 0)
        varint(UInt64(value))
    }

    /// Negative values take ten bytes, sign-extended, as protobuf's `int32` asks.
    mutating func int32(_ number: Int, _ value: Int32) {
        tag(number, 0)
        varint(UInt64(bitPattern: Int64(value)))
    }

    mutating func int64(_ number: Int, _ value: Int64) {
        tag(number, 0)
        varint(UInt64(bitPattern: value))
    }

    mutating func bool(_ number: Int, _ value: Bool) {
        tag(number, 0)
        varint(value ? 1 : 0)
    }

    mutating func string(_ number: Int, _ value: String) {
        delimited(number, value.utf8)
    }

    mutating func bytes(_ number: Int, _ value: Data) {
        delimited(number, value)
    }

    mutating func message(_ number: Int, _ value: some ProtoMessage) {
        var nested = ProtoWriter()
        value.encode(to: &nested)
        delimited(number, nested.bytes)
    }

    mutating func unknown(_ fields: ProtoUnknownFields) {
        bytes.append(contentsOf: fields.data)
    }
}

struct ProtoReader {
    private let buffer: [UInt8]
    private var position = 0
    private var fieldStart = 0
    private(set) var fieldNumber = 0
    private var wireType = 0

    init(bytes: [UInt8]) {
        self.buffer = bytes
    }

    /// Reads the next tag; false at the end of the bytes.
    mutating func nextField() throws -> Bool {
        guard position < buffer.count else { return false }
        fieldStart = position
        let key = try rawVarint()
        guard (1...maxFieldNumber).contains(key >> 3) else { throw ProtoDecodingError.invalidFieldNumber }
        fieldNumber = Int(key >> 3)
        wireType = Int(key & 7)
        return true
    }

    private mutating func rawVarint() throws -> UInt64 {
        var value: UInt64 = 0
        var shift: UInt64 = 0
        while true {
            guard position < buffer.count else { throw ProtoDecodingError.truncated }
            let byte = buffer[position]
            position += 1
            // The tenth byte holds bit 63 only.
            guard shift < 63 || (shift == 63 && byte <= 1) else { throw ProtoDecodingError.malformedVarint }
            value |= UInt64(byte & 0x7F) << shift
            if byte & 0x80 == 0 { return value }
            shift += 7
        }
    }

    private func expect(_ type: Int) throws {
        guard wireType == type else { throw ProtoDecodingError.wireTypeMismatch(field: fieldNumber) }
    }

    private mutating func delimited() throws -> ArraySlice<UInt8> {
        try expect(2)
        let length = try rawVarint()
        guard length <= UInt64(buffer.count - position) else { throw ProtoDecodingError.truncated }
        defer { position += Int(length) }
        return buffer[position..<position + Int(length)]
    }

    mutating func varint() throws -> UInt64 {
        try expect(0)
        return try rawVarint()
    }

    mutating func string() throws -> String {
        let slice = try delimited()
        guard let value = String(bytes: slice, encoding: .utf8) else {
            throw ProtoDecodingError.invalidUTF8(field: fieldNumber)
        }
        return value
    }

    mutating func bytes() throws -> Data {
        Data(try delimited())
    }

    mutating func message<Message: ProtoMessage>(_ type: Message.Type) throws -> Message {
        try message(merging: Message())
    }

    /// A message field read again merges into its value so far, as protobuf's parsers do.
    mutating func message<Message: ProtoMessage>(merging current: Message) throws -> Message {
        var nested = ProtoReader(bytes: Array(try delimited()))
        var message = current
        try message.decode(from: &nested)
        return message
    }

    /// Steps over a field this schema does not know and keeps its bytes, tag included.
    mutating func skip(into unknown: inout ProtoUnknownFields) throws {
        switch wireType {
        case 0: _ = try rawVarint()
        case 1: try advance(8)
        case 2: _ = try delimited()
        case 5: try advance(4)
        default: throw ProtoDecodingError.unsupportedWireType(wireType)
        }
        unknown.data.append(contentsOf: buffer[fieldStart..<position])
    }

    private mutating func advance(_ count: Int) throws {
        guard buffer.count - position >= count else { throw ProtoDecodingError.truncated }
        position += count
    }
}
