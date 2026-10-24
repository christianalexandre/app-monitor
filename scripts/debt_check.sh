#!/usr/bin/env bash
# Lists the open DEBTs whose trigger names a file this branch changes (ADR-0061).
# Usage: debt_check.sh [base]   (default origin/dev). Exits 2 if the base does
# not resolve, else 0.
set -uo pipefail
base="${1:-origin/dev}"
cd "$(git rev-parse --show-toplevel)" || exit 1
if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
  echo "debt-check: base '$base' does not resolve to a commit; fetch it or pass BASE=<ref>" >&2
  exit 2
fi

field() { sed -n '/^---$/,/^---$/p' "$1" | sed -n "s/^$2:[[:space:]]*//p" | sed 's/[[:space:]]*#.*$//' | head -1; }

# Committed since the merge base, plus staged, unstaged and untracked work.
committed="$(git diff --name-only "$base...HEAD" --)" || exit 2
changed="$({
  echo "$committed"
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

# A name that is some file's whole basename (`Makefile`) never matches by stem.
basenames="$(printf '%s\n' "$changed" "$(git ls-files)" | sed 's|.*/||' | sort -u)"

names_file() { # name file
  local name="$1" file="$2" base_name stem
  if [[ "$name" == */* ]]; then
    name="${name%/}"
    [[ "/$file/" == *"/$name/"* ]]
    return
  fi
  base_name="${file##*/}"
  stem="${base_name%.*}"
  [[ "$base_name" == "$name" ]] && return
  [[ "$stem" == "$name" ]] && ! grep -qxF -- "$name" <<<"$basenames"
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
