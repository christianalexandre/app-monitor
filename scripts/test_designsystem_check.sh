#!/usr/bin/env bash
# Tests designsystem_check.sh against a throwaway tree.
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
run() { "$here/designsystem_check.sh" "$tmp" >"$tmp/out" 2>&1; }
screen() { # body
  mkdir -p "$tmp/desktop/app/src/main/kotlin"
  printf '%s\n' "$1" >"$tmp/desktop/app/src/main/kotlin/Screen.kt"
}

mkdir -p "$tmp/desktop/designsystem/src/main/kotlin"
printf '%s\n' 'import androidx.compose.material.Text' 'val c = Color(0xFF000000)' 'Modifier.padding(16.dp)' \
  >"$tmp/desktop/designsystem/src/main/kotlin/Theme.kt"

echo "designsystem_check.sh"
screen 'Modifier.width(240.dp).padding(Spacing.lg)'
run
check "design system itself and sizes pass" test $? -eq 0
for bad in 'Color(0x1F2196F3)' 'MaterialTheme.typography.caption' 'import androidx.compose.material.Text' \
  'import androidx.compose.material3.Button' 'Modifier.padding(horizontal = 12.dp)' 'Arrangement.spacedBy(8.dp)' \
  'Spacer(Modifier.height(16.dp))' 'RoundedCornerShape(4.dp)'; do
  screen "$bad"
  run
  check "rejects $bad" test $? -eq 1
  check "names the file of $bad" grep -q 'desktop/app/src/main/kotlin/Screen.kt:1' "$tmp/out"
done

((failures == 0)) || { echo "$failures failure(s)" >&2; exit 1; }
