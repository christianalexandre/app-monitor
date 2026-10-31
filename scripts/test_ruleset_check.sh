#!/usr/bin/env bash
# Tests ruleset_check.sh on copies of the repository's workflows and rulesets, each edited one way.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"

if ! command -v ruby >/dev/null 2>&1; then
  echo "ruleset_check: SKIPPED (no ruby)"
  exit 0
fi

failures=0
scratch="$root/build/test-ruleset-check"
check() { # name expected-exit expected-output-substring edit-command
  rm -rf "$scratch" && mkdir -p "$scratch/.github"
  cp -R "$root/.github/workflows" "$root/.github/rulesets" "$scratch/.github/"
  (cd "$scratch" && eval "$4")
  out="$("$here/ruleset_check.sh" "$scratch" 2>&1)"
  code=$?
  if [[ "$code" == "$2" && "$out" == *"$3"* ]]; then echo "  ok   $1"; else
    echo "  FAIL $1: expected exit $2 with '$3', got $code: $out" >&2
    failures=$((failures + 1))
  fi
}

gate=.github/workflows/release-gate.yml
check "repository as is" 0 "ruleset-check: ok" ":"
check "renamed job" 1 "'e2e-ios' is no job name on pull requests to main" \
  "sed -i.bak 's/^  e2e-ios:/  e2e-ios-sim:/' $gate"
check "matrix value changed" 1 "'desktop (windows-latest)' is no job name" \
  "sed -i.bak 's/os: \[ubuntu-latest, macos-latest, windows-latest\]/os: [ubuntu-latest, macos-latest, windows-2025]/' $gate"
check "include matrix changed" 1 "'package (macos-latest, dmg, packageDmg)' is no job name" \
  "sed -i.bak 's/task: packageDmg/task: packageDistributionForCurrentOS/' $gate"
check "gate job not required" 1 "release gate job 'samples-ios' is not a required check" \
  "sed -i.bak '/\"context\": \"samples-ios\"/d' .github/rulesets/protect-main.json"
check "dev check from a workflow not run on dev" 1 "'check' is no job name on pull requests to dev" \
  "sed -i.bak 's/branches: \[dev\]/branches: [main]/' .github/workflows/ci.yml"
rm -rf "$scratch"

if ((failures > 0)); then
  echo "ruleset_check: $failures failure(s)" >&2
  exit 1
fi
echo "ruleset_check: all tests passed"
