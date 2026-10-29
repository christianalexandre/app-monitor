#!/usr/bin/env python3
"""Generates sdk/ios's protobuf types from protocol/proto without SwiftProtobuf (ADR-0160).

Reads the proto3 subset the protocol uses: top-level messages and enums, enums nested in a message,
scalar, string, bytes, enum and message fields, `repeated`, `optional` and `oneof` of messages. Anything
else stops the generator, so the schema cannot grow past what the Swift codec writes. Names follow
protoc-gen-swift's (`Appmonitor_V1_Hello`, `requestID`, `.android`), so the SDK's code is unchanged.
Usage: swift_proto_gen.py PROTO_DIR OUT_DIR.
"""

import pathlib
import re
import sys

SCALARS = {
    # proto type: (Swift type, default, writer, reader)
    "string": ("String", "String()", "string", "try reader.string()"),
    "bytes": ("Data", "Data()", "bytes", "try reader.bytes()"),
    "bool": ("Bool", "false", "bool", "try reader.varint() != 0"),
    "int32": ("Int32", "0", "int32", "Int32(truncatingIfNeeded: try reader.varint())"),
    "int64": ("Int64", "0", "int64", "Int64(bitPattern: try reader.varint())"),
    "uint32": ("UInt32", "0", "uint32", "UInt32(truncatingIfNeeded: try reader.varint())"),
}
ACRONYMS = {"id": "ID", "url": "URL", "http": "HTTP", "https": "HTTPS"}


def fail(message):
    sys.exit(f"swift-proto: {message}")


def strip_comments(text):
    return re.sub(r"//[^\n]*", "", text)


def camel(name, upper_first=False):
    words = name.split("_")
    out = []
    for i, word in enumerate(words):
        if i == 0 and not upper_first:
            out.append(word.lower())
        elif word.lower() in ACRONYMS:
            out.append(ACRONYMS[word.lower()])
        else:
            out.append(word[:1].upper() + word[1:].lower())
    return "".join(out)


def enum_case(enum_name, value_name):
    prefix = re.sub(r"(?<!^)(?=[A-Z])", "_", enum_name).upper() + "_"
    if not value_name.startswith(prefix):
        fail(f"{value_name} lacks the prefix {prefix}")
    rest = value_name[len(prefix):].lower()
    words = rest.split("_")
    return words[0] + "".join(w[:1].upper() + w[1:] for w in words[1:])


class Parser:
    def __init__(self, text, source):
        self.tokens = re.findall(r'[A-Za-z_][A-Za-z0-9_.]*|\d+|"[^"]*"|[{}=;<>,]', strip_comments(text))
        self.pos = 0
        self.source = source

    def peek(self):
        return self.tokens[self.pos] if self.pos < len(self.tokens) else None

    def take(self, expected=None):
        token = self.peek()
        if token is None or (expected is not None and token != expected):
            fail(f"{self.source}: expected {expected!r}, got {token!r}")
        self.pos += 1
        return token

    def parse_file(self):
        items = []
        while self.peek() is not None:
            token = self.take()
            if token in ("syntax", "package", "import"):
                while self.take() != ";":
                    pass
            elif token == "message":
                items.append(self.parse_message())
            elif token == "enum":
                items.append(self.parse_enum())
            else:
                fail(f"{self.source}: unsupported {token!r}")
        return items

    def parse_enum(self):
        name = self.take()
        self.take("{")
        values = []
        while self.peek() != "}":
            value = self.take()
            self.take("=")
            values.append((value, int(self.take())))
            self.take(";")
        self.take("}")
        if not values or values[0][1] != 0:
            fail(f"enum {name} must start at 0")
        return {"kind": "enum", "name": name, "values": values}

    def parse_field(self, label=None):
        type_ = self.take()
        name = self.take()
        self.take("=")
        number = int(self.take())
        self.take(";")
        return {"label": label, "type": type_, "name": name, "number": number}

    def parse_message(self):
        name = self.take()
        self.take("{")
        fields, enums, oneofs = [], [], []
        while self.peek() != "}":
            token = self.peek()
            if token == "enum":
                self.take()
                enums.append(self.parse_enum())
            elif token == "oneof":
                self.take()
                oneof = self.take()
                self.take("{")
                members = []
                while self.peek() != "}":
                    members.append(self.parse_field("oneof"))
                self.take("}")
                oneofs.append({"name": oneof, "members": members, "number": members[0]["number"]})
            elif token in ("repeated", "optional"):
                self.take()
                fields.append(self.parse_field(token))
            elif token in ("message", "map", "reserved", "extensions"):
                fail(f"message {name}: unsupported {token!r}")
            else:
                fields.append(self.parse_field())
        self.take("}")
        return {"kind": "message", "name": name, "fields": fields, "enums": enums, "oneofs": oneofs}


class Generator:
    def __init__(self, files):
        self.messages = {}
        self.enums = {}
        for items in files.values():
            for item in items:
                swift = "Appmonitor_V1_" + item["name"]
                if item["kind"] == "message":
                    self.messages[item["name"]] = swift
                    for enum in item["enums"]:
                        self.enums[f"{item['name']}.{enum['name']}"] = f"{swift}.{enum['name']}"
                        self.enums.setdefault(enum["name"], f"{swift}.{enum['name']}")
                else:
                    self.enums[item["name"]] = swift

    def kind(self, field):
        type_ = field["type"]
        if type_ in SCALARS:
            return "scalar"
        if type_ in self.enums:
            return "enum"
        if type_ in self.messages:
            return "message"
        fail(f"unknown type {type_}")

    def swift_type(self, field):
        kind = self.kind(field)
        if kind == "scalar":
            return SCALARS[field["type"]][0]
        return self.enums[field["type"]] if kind == "enum" else self.messages[field["type"]]

    def default(self, field):
        kind = self.kind(field)
        if kind == "scalar":
            return SCALARS[field["type"]][1]
        return ".unspecified" if kind == "enum" else ".init()"

    def is_default(self, field, value):
        kind = self.kind(field)
        if kind == "enum":
            return f"{value}.rawValue == 0"
        type_ = field["type"]
        if type_ in ("string", "bytes"):
            return f"{value}.isEmpty"
        if type_ == "bool":
            return f"!{value}"
        return f"{value} == 0"

    def write(self, field, value):
        kind = self.kind(field)
        number = field["number"]
        if kind == "enum":
            return f"writer.int32({number}, Int32({value}.rawValue))"
        if kind == "message":
            return f"writer.message({number}, {value})"
        return f"writer.{SCALARS[field['type']][2]}({number}, {value})"

    def read(self, field):
        kind = self.kind(field)
        if kind == "enum":
            return f"{self.swift_type(field)}(rawValue: Int(Int32(truncatingIfNeeded: try reader.varint())))!"
        if kind == "message":
            return f"try reader.message({self.swift_type(field)}.self)"
        return SCALARS[field["type"]][3]

    def enum_lines(self, enum, indent):
        pad = "    " * indent
        cases = [(enum_case(enum["name"], value), number) for value, number in enum["values"]]
        lines = [f"{pad}enum {enum['name']}: Hashable, Sendable, CaseIterable {{"]
        lines += [f"{pad}    case {case}  // = {number}" for case, number in cases]
        lines += [f"{pad}    case UNRECOGNIZED(Int)", "", f"{pad}    init() {{ self = .{cases[0][0]} }}", ""]
        lines.append(f"{pad}    init?(rawValue: Int) {{")
        lines.append(f"{pad}        switch rawValue {{")
        lines += [f"{pad}        case {number}: self = .{case}" for case, number in cases]
        lines += [f"{pad}        default: self = .UNRECOGNIZED(rawValue)", f"{pad}        }}", f"{pad}    }}", ""]
        lines.append(f"{pad}    var rawValue: Int {{")
        lines.append(f"{pad}        switch self {{")
        lines += [f"{pad}        case .{case}: return {number}" for case, number in cases]
        lines += [f"{pad}        case .UNRECOGNIZED(let value): return value", f"{pad}        }}", f"{pad}    }}", ""]
        lines.append(f"{pad}    static let allCases: [Self] = [{', '.join('.' + c for c, _ in cases)}]")
        lines.append(f"{pad}}}")
        return lines

    def message_lines(self, message):
        swift = self.messages[message["name"]]
        members = sorted(
            [("field", f) for f in message["fields"]] + [("oneof", o) for o in message["oneofs"]],
            key=lambda m: m[1]["number"],
        )
        lines = [f"struct {swift}: ProtoMessage, Hashable, Sendable {{"]
        stored = []
        for kind, member in members:
            if kind == "oneof":
                name = camel(member["name"])
                enum = "OneOf_" + camel(member["name"], upper_first=True)
                stored.append(f"    var {name}: {enum}? = nil")
                for m in member["members"]:
                    case = camel(m["name"])
                    type_ = self.swift_type(m)
                    stored += [
                        "",
                        f"    var {case}: {type_} {{",
                        f"        get {{ if case .{case}(let value)? = {name} {{ return value }}; return {self.default(m)} }}",
                        f"        set {{ {name} = .{case}(newValue) }}",
                        "    }",
                    ]
                continue
            name = camel(member["name"])
            type_ = self.swift_type(member)
            if member["label"] == "repeated":
                stored.append(f"    var {name}: [{type_}] = []")
            elif member["label"] == "optional" or self.kind(member) == "message":
                has = "has" + camel(member["name"], upper_first=True)
                clear = "clear" + camel(member["name"], upper_first=True)
                stored += [
                    f"    var {name}: {type_} {{",
                    f"        get {{ _{name} ?? {self.default(member)} }}",
                    f"        set {{ _{name} = newValue }}",
                    "    }",
                    f"    var {has}: Bool {{ _{name} != nil }}",
                    f"    mutating func {clear}() {{ _{name} = nil }}",
                    f"    private var _{name}: {type_}? = nil",
                ]
            else:
                stored.append(f"    var {name}: {type_} = {self.default(member)}")
        lines += stored
        lines += ["", "    var unknownFields = ProtoUnknownFields()", "", "    init() {}", ""]
        for kind, member in members:
            if kind == "oneof":
                cases = ", ".join(f"{camel(m['name'])}({self.swift_type(m)})" for m in member["members"])
                lines += [f"    enum OneOf_{camel(member['name'], upper_first=True)}: Hashable, Sendable {{", f"        case {cases}", "    }", ""]
        for enum in message["enums"]:
            lines += self.enum_lines(enum, 1) + [""]
        lines.append("    func encode(to writer: inout ProtoWriter) {")
        for kind, member in members:
            if kind == "oneof":
                lines.append(f"        switch {camel(member['name'])} {{")
                for m in member["members"]:
                    lines.append(f"        case .{camel(m['name'])}(let value)?: {self.write(m, 'value')}")
                lines += ["        case nil: break", "        }"]
                continue
            name = camel(member["name"])
            if member["label"] == "repeated":
                lines.append(f"        for value in {name} {{ {self.write(member, 'value')} }}")
            elif member["label"] == "optional" or self.kind(member) == "message":
                lines.append(f"        if let value = _{name} {{ {self.write(member, 'value')} }}")
            else:
                lines.append(f"        if !({self.is_default(member, name)}) {{ {self.write(member, name)} }}")
        lines += ["        writer.unknown(unknownFields)", "    }", ""]
        lines.append("    mutating func decodeField(_ number: Int, from reader: inout ProtoReader) throws {")
        lines.append("        switch number {")
        for kind, member in members:
            if kind == "oneof":
                for m in member["members"]:
                    lines.append(f"        case {m['number']}: {camel(member['name'])} = .{camel(m['name'])}({self.read(m)})")
                continue
            name = camel(member["name"])
            if member["label"] == "repeated":
                lines.append(f"        case {member['number']}: {name}.append({self.read(member)})")
            elif member["label"] == "optional" or self.kind(member) == "message":
                lines.append(f"        case {member['number']}: _{name} = {self.read(member)}")
            else:
                lines.append(f"        case {member['number']}: {name} = {self.read(member)}")
        lines += ["        default: try reader.skip(into: &unknownFields)", "        }", "    }", "}"]
        return lines


def main():
    if len(sys.argv) != 3:
        fail("usage: swift_proto_gen.py PROTO_DIR OUT_DIR")
    proto_dir, out_dir = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    files = {p: Parser(p.read_text(), p.name).parse_file() for p in sorted(proto_dir.glob("*.proto"))}
    generator = Generator(files)
    for path, items in files.items():
        lines = [
            "// swift-format-ignore-file",
            f"// DO NOT EDIT. Generated by scripts/swift_proto_gen.py from {path.name} (ADR-0160).",
            "",
            "import Foundation",
            "",
        ]
        for item in items:
            if item["kind"] == "enum":
                lines += [line.replace(f"enum {item['name']}:", f"enum Appmonitor_V1_{item['name']}:", 1) if i == 0 else line
                          for i, line in enumerate(generator.enum_lines(item, 0))]
            else:
                lines += generator.message_lines(item)
            lines.append("")
        (out_dir / f"appmonitor_v1_{path.stem}.pb.swift").write_text("\n".join(lines).rstrip("\n") + "\n")


if __name__ == "__main__":
    main()
