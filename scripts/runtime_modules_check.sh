#!/usr/bin/env bash
# Fails when the desktop's runtime image lacks a module its jars use (ADR-0230): what jdeps finds,
# through Compose's suggestRuntimeModules, against modules(...) in desktop/app/build.gradle.kts.
# SUGGESTED and CONFIGURED replace the two modules(...) lines (scripts/test_runtime_modules_check.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
suggested="${SUGGESTED-$(./gradlew -q :desktop:app:suggestRuntimeModules | grep 'modules(' | tail -1)}"
configured="${CONFIGURED-$(awk '/^ *modules\(/ { on = 1 } on { print } on && /\)/ { exit }' desktop/app/build.gradle.kts)}"
names() { grep -o '"[^"]*"' <<<"$1" | tr -d '"' | sort -u; }
[[ -n "$(names "$suggested")" ]] || { echo "runtime-modules: no suggestion read from :desktop:app:suggestRuntimeModules" >&2; exit 2; }
missing="$(comm -23 <(names "$suggested") <(names "$configured") | tr '\n' ' ')"
if [[ -n "$missing" ]]; then
  echo "runtime-modules: desktop/app/build.gradle.kts's modules(...) lacks: ${missing% }" >&2
  exit 1
fi
echo "runtime-modules: ok"
