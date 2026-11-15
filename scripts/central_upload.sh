#!/usr/bin/env bash
# Usage: central_upload.sh REPO_DIR [--bundle-only]
# Zips a signed Maven repository into a Central Portal bundle (build/central-bundle.zip) and,
# unless --bundle-only, uploads it with publishingType AUTOMATIC and waits for PUBLISHED
# (ADR-0215, ADR-0217). Needs MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD to upload.
set -euo pipefail
cd "$(dirname "$0")/.."

repo="${1:-}"
[[ -d "$repo/io" ]] || { echo "usage: $0 REPO_DIR [--bundle-only] (REPO_DIR holds io/…)" >&2; exit 2; }
url="${CENTRAL_URL:-https://central.sonatype.com}"
poll="${CENTRAL_POLL_SECONDS:-15}"
deadline=$((SECONDS + ${CENTRAL_TIMEOUT_SECONDS:-1800}))
bundle="$PWD/build/central-bundle.zip"

rm -f "$bundle"
mkdir -p "$(dirname "$bundle")"
# Central computes the metadata itself; a version's files are all it takes.
(cd "$repo" && zip -qr "$bundle" io -x '*maven-metadata.xml*')
echo "central: bundle $(unzip -Z1 "$bundle" | grep -vc '/$') files, $(wc -c <"$bundle" | tr -d ' ') bytes"
[[ "${2:-}" != --bundle-only ]] || exit 0

[[ -n "${MAVEN_CENTRAL_USERNAME:-}" && -n "${MAVEN_CENTRAL_PASSWORD:-}" ]] ||
  { echo "central: MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD are needed to upload" >&2; exit 2; }
auth="Authorization: Bearer $(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')"
version="$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"

id="$(curl -fsS -X POST -H "$auth" -F "bundle=@$bundle" \
  "$url/api/v1/publisher/upload?name=appmonitor-$version&publishingType=AUTOMATIC")"
echo "central: deployment $id"
while :; do
  status="$(curl -fsS -X POST -H "$auth" "$url/api/v1/publisher/status?id=$id")"
  state="$(printf '%s' "$status" | sed -n 's/.*"deploymentState" *: *"\([A-Z]*\)".*/\1/p')"
  echo "central: $state"
  case "$state" in
    PUBLISHED) exit 0 ;;
    FAILED)
      echo "central: $status" >&2
      exit 1
      ;;
  esac
  ((SECONDS < deadline)) || { echo "central: still $state after the timeout; see the Portal's Deployments" >&2; exit 1; }
  sleep "$poll"
done
