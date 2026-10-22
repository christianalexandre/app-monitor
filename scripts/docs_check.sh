#!/usr/bin/env bash
# Validates docs/ structure: frontmatter, indexes and ADR/DEBT references.
set -uo pipefail
cd "$(dirname "$0")/.."

errors=0
err() { echo "docs-check: $1" >&2; errors=$((errors + 1)); }
field() { sed -n '/^---$/,/^---$/p' "$1" | sed -n "s/^$2:[[:space:]]*//p" | sed 's/[[:space:]]*#.*$//' | head -1; }

[[ -L CLAUDE.md && "$(readlink CLAUDE.md)" == "AGENTS.md" ]] || err "CLAUDE.md must be a symlink to AGENTS.md"

check_records() { # folder prefix allowed-statuses
  local dir="$1" prefix="$2" statuses="$3"
  for f in "$dir"/[0-9][0-9][0-9][0-9]-*.md; do
    [[ -e "$f" ]] || continue
    local name num id status
    name="$(basename "$f")"; num="${name%%-*}"
    id="$(field "$f" id)"; status="$(field "$f" status)"
    [[ "$name" =~ ^[0-9]{4}-[a-z0-9]+(-[a-z0-9]+)*\.md$ ]] || err "$f: name must be NNNN-kebab-slug.md"
    [[ "$id" == "$prefix-$num" ]] || err "$f: id '$id' must be $prefix-$num"
    [[ -n "$(field "$f" title)" ]] || err "$f: missing title"
    local date_field=date; [[ "$prefix" == "PLAN" ]] && date_field=started
    if [[ "$prefix" != "PLAN" || "$status" != "Draft" ]]; then
      [[ "$(field "$f" $date_field)" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || err "$f: $date_field must be YYYY-MM-DD"
    fi
    [[ " $statuses " == *" $status "* ]] || err "$f: status '$status' not in {$statuses}"
    grep -q "($name)" "$dir/README.md" || err "$f: missing from $dir/README.md index"
    if [[ "$prefix" == "ADR" && "$status" == "Superseded" ]]; then
      [[ -n "$(field "$f" superseded_by)" ]] || err "$f: Superseded needs superseded_by"
    fi
    if [[ "$prefix" == "DEBT" ]]; then
      local trigger; trigger="$(field "$f" trigger)"
      [[ -n "$trigger" && "$trigger" != "TODO" ]] || err "$f: trigger is required (an event)"
    fi
  done
}
check_records docs/adr ADR "Proposed Accepted Superseded Rejected"
check_records docs/tech-debt DEBT "Open Paid Dropped"
check_records docs/plans PLAN "Draft Active Done Abandoned"

active="$(grep -l '^status: Active' docs/plans/[0-9]*.md 2>/dev/null | wc -l | tr -d ' ')"
(( active <= 1 )) || err "docs/plans: $active plans are Active, at most one may be"
for f in docs/plans/[0-9][0-9][0-9][0-9]-*.md; do
  [[ -e "$f" ]] || continue
  case "$(field "$f" status)" in
    Done|Abandoned) [[ "$(field "$f" finished)" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || err "$f: closed plan needs finished date"
                    grep -q '^> Filled when the plan closes' "$f" && err "$f: closed plan needs its Outcome filled" ;;
  esac
done

for dir in docs/sdd docs/guidelines; do
  for f in "$dir"/[0-9][0-9]_*.md; do
    [[ -e "$f" ]] || continue
    grep -q "($(basename "$f"))" "$dir/README.md" || err "$f: missing from $dir/README.md index"
  done
done

refs="$(git ls-files -co --exclude-standard -- ':!docs/*/_template.md' ':!scripts/docs_check.sh' \
  | xargs grep -ohE '\b(ADR|DEBT|PLAN)-[0-9]{4}\b' 2>/dev/null | sort -u)"
for ref in $refs; do
  kind="${ref%%-*}"; num="${ref#*-}"
  case "$kind" in ADR) dir="docs/adr" ;; DEBT) dir="docs/tech-debt" ;; PLAN) dir="docs/plans" ;; esac
  ls "$dir/$num"-*.md >/dev/null 2>&1 || err "$ref is referenced but $dir/$num-*.md does not exist"
done

(( errors == 0 )) && echo "docs-check: ok" || exit 1
