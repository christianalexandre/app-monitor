#!/usr/bin/env bash
# Reads `xcrun simctl list devices` on stdin, prints the devices of iOS SIM_OS, else of the last iOS
# section with any: simctl lists runtimes oldest first, and xcodebuild's `name=` alone means the newest.
set -euo pipefail
awk -v os="${SIM_OS:-}" '
  /^-- / {
    if (os != "") on = ($0 == "-- iOS " os " --"); else on = ($0 ~ /^-- iOS /)
    section = ""
    next
  }
  on { section = section $0 "\n"; kept = section }
  END { printf "%s", kept }
'
