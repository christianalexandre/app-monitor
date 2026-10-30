#!/usr/bin/env bash
# Tests scripts/swift_proto_gen.py on small schemas (ADR-0160): what the Swift
# codec cannot read stops the generator, what it can is written right. Python 3 only.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

failures=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then echo "  ok   $1"; else
    echo "  FAIL $1: expected '$2', got '$3'" >&2
    failures=$((failures + 1))
  fi
}

# Generates $1 (the body of a proto3 file); prints "stopped: <message>" or the Swift.
gen() {
  rm -rf "$work/in" "$work/out"
  mkdir -p "$work/in" "$work/out"
  printf 'syntax = "proto3";\npackage appmonitor.v1;\n%s\n' "$1" >"$work/in/probe.proto"
  local err
  if err="$(python3 -I "$here/swift_proto_gen.py" "$work/in" "$work/out" 2>&1)"; then
    cat "$work/out/appmonitor_v1_probe.pb.swift"
  else
    echo "stopped: $err"
  fi
}

stops() { # name proto expected-message-part
  local out
  out="$(gen "$2")"
  if [[ "$out" == stopped:*"$3"* ]]; then check "$1" stopped stopped; else check "$1" "stopped: …$3…" "$(head -1 <<<"$out")"; fi
}

# Wire writes repeated scalars packed (proto3's default); the codec reads one varint per tag.
stops "repeated int32 stops" 'message Probe { repeated int32 codes = 1; }' "packed"
stops "repeated bool stops" 'message Probe { repeated bool flags = 1; }' "packed"
stops "repeated enum stops" 'enum Mode { MODE_UNSPECIFIED = 0; } message Probe { repeated Mode modes = 1; }' "packed"
stops "field number 0 stops" 'message Probe { string a = 0; }' "field number"
stops "field number 2^29 stops" 'message Probe { string a = 536870912; }' "field number"
stops "reserved field number 19000 stops" 'message Probe { string a = 19000; }' "field number"
stops "a duplicated field number stops" 'message Probe { string a = 1; string b = 1; }' "field number"
stops "a field option stops" 'message Probe { string a = 1 [deprecated = true]; }' "expected ';'"

out="$(gen 'enum Mode { MODE_UNSPECIFIED = 0; MODE_BACK = -1; } message Probe { Mode mode = 1; }')"
check "a negative enum value keeps its sign" "        case -1: self = .back" "$(grep -F 'case -1:' <<<"$out")"

out="$(gen 'message Probe { repeated string names = 1; uint32 max = 536870911; }')"
check "repeated string is generated" "        case 1: names.append(try reader.string())" "$(grep -F 'case 1:' <<<"$out")"
check "the highest field number is generated" "1" "$(grep -cF 'case 536870911:' <<<"$out")"

if ((failures > 0)); then
  echo "swift_proto_gen: $failures failure(s)" >&2
  exit 1
fi
echo "swift_proto_gen: all tests passed"
