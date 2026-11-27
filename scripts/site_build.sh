#!/usr/bin/env bash
# Usage: site_build.sh
# Builds the docs site in strict mode into build/site, with zensical from site_requirements.txt in build/site-venv
# (ADR-0234). Needs Python 3.11 or newer: PYTHON picks it, else the first python3.13, 3.12, 3.11 or python3 that is.
set -euo pipefail
cd "$(dirname "$0")/.."

python="${PYTHON:-}"
if [[ -z "$python" ]]; then
  for candidate in python3.13 python3.12 python3.11 python3; do
    if command -v "$candidate" >/dev/null && "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 11))'; then
      python="$candidate"; break
    fi
  done
fi
[[ -n "$python" ]] || { echo "site: Python 3.11 or newer is needed (PYTHON=<path>)" >&2; exit 1; }

venv=build/site-venv
if [[ ! -x "$venv/bin/zensical" ]] || ! cmp -s scripts/site_requirements.txt "$venv/requirements.txt"; then
  rm -rf "$venv"
  "$python" -m venv "$venv"
  "$venv/bin/pip" install --quiet --disable-pip-version-check -r scripts/site_requirements.txt
  cp scripts/site_requirements.txt "$venv/requirements.txt"
fi

"$venv/bin/python" scripts/site_src.py . build/site-src
"$venv/bin/zensical" build --strict --clean
echo "site: ok, build/site"
