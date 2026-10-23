#!/usr/bin/env bash
# Tests debt_check.sh against a throwaway repository with fake DEBTs.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

failures=0
check() { # name condition...
  local name="$1"
  shift
  if "$@"; then echo "  ok   $name"; else echo "  FAIL $name" >&2; failures=$((failures + 1)); fi
}
has() { grep -qF -- "$1" "$tmp/out"; }
lacks() { ! grep -qF -- "$1" "$tmp/out"; }
not_line() { ! grep -q -- "$1" "$tmp/out"; }

debt() { # number status trigger [section]
  cat >"$tmp/repo/docs/tech-debt/$1-fake.md" <<EOF
---
id: DEBT-$1
title: Fake $1
status: $2
date: 2026-10-23
trigger: $3
paid_date:
---

# DEBT-$1 — Fake $1

## Trigger

${4:-}

## How it was paid
EOF
}

git init -q -b dev "$tmp/repo"
git -C "$tmp/repo" config user.email t@example.com
git -C "$tmp/repo" config user.name t
mkdir -p "$tmp/repo/docs/tech-debt" "$tmp/repo/sdk/core/src" "$tmp/repo/sdk/no-op"
debt 0001 Open 'The next PR that touches `Client.kt`'
debt 0002 Paid 'The next PR that touches `Client.kt`'
debt 0003 Open 'The next PR that touches one of the files listed below' \
  'The next PR that touches `Masking.swift` or `sdk/no-op` pays them.'
debt 0004 Open 'The next PR that touches `MonitorClient` or its stop path'
debt 0005 Open '`make check` over 3 minutes, or `gradle/libs.versions.toml` changes'
debt 0006 Open 'A developer reports a bug'
touch "$tmp/repo/sdk/core/src/Client.kt" "$tmp/repo/sdk/core/src/MonitorClient.kt" \
  "$tmp/repo/sdk/no-op/build.gradle.kts" "$tmp/repo/Makefile"
git -C "$tmp/repo" add -A
git -C "$tmp/repo" commit -qm base
git -C "$tmp/repo" switch -qc work

echo "test-debt-check: nothing changed"
(cd "$tmp/repo" && "$here/debt_check.sh" dev) >"$tmp/out"
code=$?
check "exits 0" test "$code" -eq 0
check "says no trigger fired" has "no open DEBT trigger names a changed file"

echo "test-debt-check: committed, unstaged and untracked changes"
echo x >>"$tmp/repo/sdk/core/src/Client.kt"
git -C "$tmp/repo" commit -qam client
echo x >>"$tmp/repo/sdk/core/src/MonitorClient.kt"
mkdir -p "$tmp/repo/sdk/ios"
touch "$tmp/repo/sdk/ios/Masking.swift" "$tmp/repo/Makefile.bak"
echo x >>"$tmp/repo/Makefile"
(cd "$tmp/repo/sdk" && "$here/debt_check.sh" dev) >"$tmp/out"
code=$?
check "exits 0 from a subdirectory" test "$code" -eq 0
check "committed file by basename" has "DEBT-0001 Fake 0001: sdk/core/src/Client.kt"
check "paid DEBT ignored" lacks "DEBT-0002"
check "untracked file named in the Trigger section" has "DEBT-0003 Fake 0003: sdk/ios/Masking.swift"
check "unstaged file by stem" has "DEBT-0004 Fake 0004: sdk/core/src/MonitorClient.kt"
check "basename does not match a longer name" not_line "^DEBT-0001 .*MonitorClient.kt"
check "command in backticks is not a file" lacks "DEBT-0005"
check "trigger without a file never fires" lacks "DEBT-0006"
check "no-trigger line absent" lacks "no open DEBT trigger"

echo "test-debt-check: directory and path names"
git -C "$tmp/repo" add -A
git -C "$tmp/repo" commit -qm more
echo x >>"$tmp/repo/sdk/no-op/build.gradle.kts"
mkdir -p "$tmp/repo/gradle"
touch "$tmp/repo/gradle/libs.versions.toml"
(cd "$tmp/repo" && "$here/debt_check.sh" dev) >"$tmp/out"
check "file under a named directory" has "sdk/no-op/build.gradle.kts"
check "file by full path" has "DEBT-0005 Fake 0005: gradle/libs.versions.toml"
check "each DEBT on one line" test "$(grep -c '^DEBT-0003 ' "$tmp/out")" -eq 1

((failures == 0)) && echo "test-debt-check: ok" || { echo "test-debt-check: $failures failed" >&2; exit 1; }
