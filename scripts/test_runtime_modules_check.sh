#!/usr/bin/env bash
# Tests scripts/runtime_modules_check.sh on stand-in modules(...) lines (ADR-0230).
set -euo pipefail
cd "$(dirname "$0")/.."
failures=0
check() { # name, condition
  if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
run() { # suggested, configured → exit code; output in $out
  local code=0
  out="$(SUGGESTED="$1" CONFIGURED="$2" scripts/runtime_modules_check.sh 2>&1)" || code=$?
  return "$code"
}
code=0; run 'modules("java.sql", "java.net.http")' '  modules("java.net.http", "java.sql", "jdk.crypto.ec")' || code=$?
check "every suggested module configured passes" '[[ $code -eq 0 ]]'
code=0; run 'modules("java.sql", "java.net.http", "java.naming")' '  modules("java.sql")' || code=$?
check "a missing module fails and is named" '[[ $code -eq 1 && "$out" == *"java.naming java.net.http"* ]]'
code=0; run 'modules("java.sql", "java.net.http")' $'  modules(\n    "java.sql",\n    "java.net.http",\n  )' || code=$?
check "a list over several lines passes" '[[ $code -eq 0 ]]'
code=0; run '' '  modules("java.sql")' || code=$?
check "no suggestion read fails" '[[ $code -eq 2 ]]'
[[ $failures -eq 0 ]] || exit 1
