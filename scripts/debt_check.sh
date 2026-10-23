#!/usr/bin/env bash
# Lists the open DEBTs whose trigger names a file this branch changes (ADR-0051).
# Usage: debt_check.sh [base]   (default origin/dev). Informational: exits 0.
set -uo pipefail
base="${1:-origin/dev}"
cd "$(git rev-parse --show-toplevel)" || exit 1

field() { sed -n '/^---$/,/^---$/p' "$1" | sed -n "s/^$2:[[:space:]]*//p" | sed 's/[[:space:]]*#.*$//' | head -1; }

# Committed since the merge base, plus staged, unstaged and untracked work.
changed="$({
  git diff --name-only "$base...HEAD" --
  git diff --name-only HEAD --
  git ls-files --others --exclude-standard
} | sort -u)"

# Backticked names in the frontmatter trigger and the "## Trigger" section;
# names with a space are commands (`make check`), not files.
trigger_names() {
  {
    field "$1" trigger
    sed -n '/^## Trigger/,/^## /p' "$1"
  } | grep -o '`[^`]*`' | tr -d '`' | grep -v '[[:space:]]' | sort -u
}

names_file() { # name file
  local name="$1" file="$2" base_name stem
  if [[ "$name" == */* ]]; then
    name="${name%/}"
    [[ "$file" == "$name" || "$file" == "$name"/* || "$file" == */"$name" ]]
    return
  fi
  base_name="${file##*/}"
  stem="${base_name%.*}"
  [[ "$base_name" == "$name" || "$stem" == "$name" ]]
}

found=0
for debt in docs/tech-debt/[0-9][0-9][0-9][0-9]-*.md; do
  [[ -e "$debt" && "$(field "$debt" status)" == "Open" ]] || continue
  hits=""
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    while IFS= read -r file; do
      [[ -n "$file" ]] || continue
      names_file "$name" "$file" && [[ " $hits " != *" $file "* ]] && hits="$hits $file"
    done <<<"$changed"
  done < <(trigger_names "$debt")
  [[ -n "$hits" ]] || continue
  found=$((found + 1))
  echo "$(field "$debt" id) $(field "$debt" title):$hits"
done
((found > 0)) || echo "debt-check: no open DEBT trigger names a changed file"
