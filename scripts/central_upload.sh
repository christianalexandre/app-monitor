#!/usr/bin/env bash
# Usage: central_upload.sh REPO_DIR [--bundle-only]
# Zips a signed Maven repository into build/central-bundle.zip and, unless --bundle-only, uploads it
# and waits for PUBLISHING; a version Central already serves is skipped (ADR-0215, ADR-0217, ADR-0231).
# Needs MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD to upload.
set -euo pipefail
cd "$(dirname "$0")/.."

repo="${1:-}"
[[ -d "$repo/io" ]] || { echo "usage: $0 REPO_DIR [--bundle-only] (REPO_DIR holds io/…)" >&2; exit 2; }
url="${CENTRAL_URL:-https://central.sonatype.com}"
maven="${CENTRAL_REPO_URL:-https://repo1.maven.org/maven2}"
poll="${CENTRAL_POLL_SECONDS:-15}"
deadline=$((SECONDS + ${CENTRAL_TIMEOUT_SECONDS:-1800}))
bundle="$PWD/build/central-bundle.zip"

rm -f "$bundle"
mkdir -p "$(dirname "$bundle")"
# Central requires .md5 and .sha1 only; more files count against its monthly limit (DEBT-0135).
(cd "$repo" && zip -qr "$bundle" io -x '*maven-metadata.xml*' '*.sha256' '*.sha512')
echo "central: bundle $(unzip -Z1 "$bundle" | grep -vc '/$') files, $(wc -c <"$bundle" | tr -d ' ') bytes"
[[ "${2:-}" != --bundle-only ]] || exit 0

[[ -n "${MAVEN_CENTRAL_USERNAME:-}" && -n "${MAVEN_CENTRAL_PASSWORD:-}" ]] ||
  { echo "central: MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD are needed to upload" >&2; exit 2; }
auth="Authorization: Bearer $(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')"
version="$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"
pom="$(cd "$repo" && find io -name '*.pom' | sort | head -1)"
[[ -n "$pom" ]] || { echo "central: no .pom under $repo/io" >&2; exit 2; }
if curl -sSf -o /dev/null "$maven/$pom" 2>/dev/null; then
  echo "central: $pom already on Maven Central; nothing uploaded"
  exit 0
fi

id="$(curl --fail-with-body -sS -X POST -H "$auth" -F "bundle=@$bundle" \
  "$url/api/v1/publisher/upload?name=appmonitor-$version&publishingType=AUTOMATIC")" ||
  { echo "central: upload refused: $id" >&2; exit 1; }
echo "central: deployment $id"
while :; do
  status="$(curl --fail-with-body -sS -X POST -H "$auth" "$url/api/v1/publisher/status?id=$id")" ||
    { echo "central: status refused: $status" >&2; exit 1; }
  state="$(printf '%s' "$status" | sed -n 's/.*"deploymentState" *: *"\([A-Z]*\)".*/\1/p')"
  echo "central: $state"
  case "$state" in
    PUBLISHED) exit 0 ;;
    PUBLISHING)
      echo "central: validated; Maven Central serves it once the Portal reports PUBLISHED, which took up to two hours"
      exit 0
      ;;
    FAILED)
      echo "central: $status" >&2
      exit 1
      ;;
  esac
  ((SECONDS < deadline)) || { echo "central: still $state after the timeout; see the Portal's Deployments" >&2; exit 1; }
  sleep "$poll"
done
