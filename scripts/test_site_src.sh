#!/usr/bin/env bash
# Tests scripts/site_src.py on a crafted tree: links inside the site follow it, the rest go to GitHub (ADR-0234).
set -euo pipefail
cd "$(dirname "$0")/.."
work="$PWD/build/test-site-src"
rm -rf "$work"
mkdir -p "$work/docs/guide" "$work/docs/screenshots" "$work/docs/adr"
touch "$work/docs/screenshots/demo.gif" "$work/LICENSE"
printf '%s\n' '# App' '![demo](docs/screenshots/demo.gif)' '[iOS](docs/guide/ios.md#run-script) [web](https://example.com)' \
  '[license](LICENSE) [top](#app)' '```' '[in code](docs/guide/ios.md)' '```' > "$work/README.md"
printf '%s\n' '# Guides' '[iOS](ios.md)' > "$work/docs/guide/README.md"
printf '%s\n' '# iOS' '[home](../../README.md) [why](../adr/0001-x.md#context) ![shot](../screenshots/demo.gif)' > "$work/docs/guide/ios.md"
python3 scripts/site_src.py "$work" "$work/out" >/dev/null
failures=0
check() { # name, condition
  if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
index="$(cat "$work/out/index.md")" ios="$(cat "$work/out/guide/ios.md")"
check "the README is the home page, the guides under guide/" '[[ -f "$work/out/index.md" && -f "$work/out/guide/index.md" && -f "$work/out/guide/ios.md" ]]'
check "a guide link from the home page follows the guide, anchor kept" '[[ "$index" == *"[iOS](guide/ios.md#run-script)"* ]]'
check "an image is copied to assets/ and linked there" '[[ -f "$work/out/assets/demo.gif" && "$index" == *"(assets/demo.gif)"* && "$ios" == *"(../assets/demo.gif)"* ]]'
check "a file outside the site goes to GitHub" '[[ "$index" == *"(https://github.com/christianalexandre/app-monitor/blob/main/LICENSE)"* && "$ios" == *"/blob/main/docs/adr/0001-x.md#context)"* ]]'
check "a guide's link to the README goes to the home page" '[[ "$ios" == *"[home](../index.md)"* ]]'
check "external links, anchors and code are left as they are" '[[ "$index" == *"(https://example.com)"* && "$index" == *"(#app)"* && "$index" == *"[in code](docs/guide/ios.md)"* ]]'
rm -rf "$work"
[[ $failures -eq 0 ]] || exit 1
