#!/usr/bin/env bash
# Tests sim_list.sh on a captured `xcrun simctl list devices` output; needs no Xcode.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

failures=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then echo "  ok   $1"; else
    echo "  FAIL $1: expected '$2', got '$3'" >&2
    failures=$((failures + 1))
  fi
}

out="$("$here/sim_list.sh" <<'LIST'
== Devices ==
-- iOS 26.5 --
    iPhone 17 Pro (27749C4B-B100-4F58-9296-4138D6B9F6DF) (Booted) 
    iPhone SE (3rd generation) (0F3C2A1B-1234-4ABC-9DEF-0123456789AB) (Shutdown) 
    iPad mini (A17 Pro) (DA3B13AC-AFC4-4ED8-A6B0-D60CB63FF69A) (Shutdown) 
    iPhone 16e (906ABD69-8C55-439F-884E-0B7F8A4C4B9F) (Shutdown) (unavailable, runtime profile not found) 
LIST
)"

check "plain name" "27749C4B-B100-4F58-9296-4138D6B9F6DF iPhone 17 Pro" "$(sed -n 1p <<<"$out")"
check "name with parentheses" "0F3C2A1B-1234-4ABC-9DEF-0123456789AB iPhone SE (3rd generation)" "$(sed -n 2p <<<"$out")"
check "trailing state ignored" "906ABD69-8C55-439F-884E-0B7F8A4C4B9F iPhone 16e" "$(sed -n 3p <<<"$out")"
check "iPads skipped" "3" "$(wc -l <<<"$out" | tr -d ' ')"

list='== Devices ==
-- iOS 16.0 --
    iPhone 14 (38CF7548-679F-436C-9CBB-A990724C70C2) (Shutdown) 
-- iOS 26.5 --
    iPhone 17 Pro (27749C4B-B100-4F58-9296-4138D6B9F6DF) (Shutdown) 
-- iOS 27.0 --
-- tvOS 26.5 --
    Apple TV (11111111-2222-4333-8444-555555555555) (Shutdown) '
check "newest iOS with devices" "    iPhone 17 Pro (27749C4B-B100-4F58-9296-4138D6B9F6DF) (Shutdown) " "$("$here/sim_runtime.sh" <<<"$list")"
check "SIM_OS's iOS" "    iPhone 14 (38CF7548-679F-436C-9CBB-A990724C70C2) (Shutdown) " "$(SIM_OS=16.0 "$here/sim_runtime.sh" <<<"$list")"
check "SIM_OS not installed" "" "$(SIM_OS=15.0 "$here/sim_runtime.sh" <<<"$list")"

if ((failures > 0)); then
  echo "sim_list: $failures failure(s)" >&2
  exit 1
fi
echo "sim_list: all tests passed"
