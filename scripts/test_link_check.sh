#!/usr/bin/env bash
# Tests scripts/link_check.py on a crafted tree: what resolves passes, a missing file or heading fails (ADR-0233).
set -euo pipefail
cd "$(dirname "$0")/.."
work="$PWD/build/test-link-check"
rm -rf "$work"
mkdir -p "$work/docs/guide" "$work/docs/screenshots"
touch "$work/docs/screenshots/demo.gif"
cat > "$work/docs/guide/ios.md" <<'MD'
# iOS

## 4. Paste the Run Script

## `make swift` and you
MD
failures=0
check() { # name, condition
  if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
readme() { printf '%s\n' "$@" > "$work/README.md"; }
run() { code=0; out="$(python3 scripts/link_check.py "$work" 2>&1)" || code=$?; }

readme '# App' '![demo](docs/screenshots/demo.gif)' '[iOS](docs/guide/ios.md#4-paste-the-run-script)' \
  '[make](docs/guide/ios.md#make-swift-and-you) [web](https://example.com/x) [here](#app)' \
  '<img src="docs/screenshots/demo.gif" width="600">' '```' '[in code](missing.md)' '```' '`[inline](missing.md)`'
run
check "links, images, anchors and HTML that resolve pass; code and external links are skipped" '[[ $code -eq 0 ]]'
readme '# App' '![demo](docs/screenshots/gone.gif)'
run
check "a missing image fails" '[[ $code -eq 1 && "$out" == *"docs/screenshots/gone.gif: no such file"* ]]'
readme '# App' '[iOS](docs/guide/ios.md#5-run)'
run
check "a missing heading fails" '[[ $code -eq 1 && "$out" == *"no heading #5-run"* ]]'
readme '# App' '[back](README.md)'
printf '%s\n' '# Guide' '[home](../../README.md) [gone](android.md)' > "$work/docs/guide/kmp.md"
run
check "a guide's link to a missing guide fails, relative to the guide" '[[ $code -eq 1 && "$out" == *"docs/guide/kmp.md: android.md: no such file"* && "$out" != *"README.md: no such"* ]]'
rm -rf "$work"
[[ $failures -eq 0 ]] || exit 1
