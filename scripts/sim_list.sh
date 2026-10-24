#!/usr/bin/env bash
# Reads `xcrun simctl list devices` on stdin, prints "<udid> <name>" per iPhone.
# The UDID is the last parenthesised UUID: names such as "iPhone SE (3rd generation)" keep theirs.
set -euo pipefail
hex='[0-9A-F]'
uuid="$hex\{8\}-$hex\{4\}-$hex\{4\}-$hex\{4\}-$hex\{12\}"
sed -n "s/^ *\(iPhone.*\) (\($uuid\)) .*/\2 \1/p"
