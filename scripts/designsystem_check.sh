#!/usr/bin/env bash
# ADR-0133: outside desktop/designsystem, no raw color, no Material theme or component, no raw spacing or radius.
# Usage: designsystem_check.sh [root]; prints each offending line, exits 1 when there is one.
set -uo pipefail
root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"

rules=(
  'Color\(0x @@ raw color: add a token to desktop/designsystem'
  'MaterialTheme\. @@ Material theme: use AppMonitorTheme, TextRole or a component'
  '^import androidx\.compose\.material3?\. @@ Material import: use the design system component'
  '(padding|spacedBy|RoundedCornerShape)\([^)]*[0-9]\.dp @@ raw spacing or radius: use Spacing or Radius'
  'Spacer\(.*[0-9]\.dp @@ raw spacing: use Spacing'
)

found=0
dirs=()
for dir in "$root"/desktop/*/src/main "$root"/desktop/feature/*/src/main; do
  [[ -d "$dir" && "$dir" != "$root/desktop/designsystem/src/main" ]] && dirs+=("$dir")
done
if ((${#dirs[@]})); then
  for rule in "${rules[@]}"; do
    pattern="${rule%% @@ *}"
    why="${rule#* @@ }"
    hits="$(grep -rnE --include='*.kt' "$pattern" "${dirs[@]}")"
    (($? > 1)) && { echo "designsystem-check: bad pattern $pattern" >&2; exit 2; }
    while IFS= read -r line; do
      [[ -n "$line" ]] || continue
      echo "designsystem-check: ${line#"$root"/}: $why" >&2
      found=1
    done <<<"$hits"
  done
fi
exit "$found"
