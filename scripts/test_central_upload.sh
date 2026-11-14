#!/usr/bin/env bash
# Tests scripts/central_upload.sh against a stub Central Portal on 127.0.0.1 (ADR-0217).
set -euo pipefail
cd "$(dirname "$0")/.."
work="$PWD/build/test-central-upload"
rm -rf "$work"
mkdir -p "$work/repo/io/github/x/a/1.0.0"
touch "$work/repo/io/github/x/a/1.0.0/a-1.0.0.jar" "$work/repo/io/github/x/a/1.0.0/a-1.0.0.jar.asc" \
  "$work/repo/io/github/x/a/maven-metadata.xml" "$work/repo/io/github/x/a/maven-metadata.xml.md5"
failures=0
check() { # name, condition
  if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

cat >"$work/stub.py" <<'PY'
import http.server, os, sys
final, log = sys.argv[1], sys.argv[2]
polls = []
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        with open(log, "a") as f:
            f.write(f"{self.path} {self.headers.get('Authorization')} {len(body)}\n")
        if self.path.startswith("/api/v1/publisher/upload"):
            out = "dep-1"
        else:
            polls.append(1)
            state = "VALIDATING" if len(polls) == 1 else final
            out = '{"deploymentId":"dep-1","deploymentState":"%s","errors":{"x":["bad"]}}' % state
        self.send_response(200); self.end_headers(); self.wfile.write(out.encode())
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(log + ".port", "w").write(str(s.server_port))
s.serve_forever()
PY

run() { # final state → exit code of the script
  local log="$work/requests-$1.log" code=0 pid
  python3 "$work/stub.py" "$1" "$log" &
  pid=$!
  for _ in $(seq 50); do [[ -s "$log.port" ]] && break; sleep 0.1; done
  CENTRAL_URL="http://127.0.0.1:$(cat "$log.port")" CENTRAL_POLL_SECONDS=0 \
    MAVEN_CENTRAL_USERNAME=user MAVEN_CENTRAL_PASSWORD=token \
    scripts/central_upload.sh "$work/repo" >"$work/out-$1.log" 2>&1 || code=$?
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  echo "$code"
}

code=0
scripts/central_upload.sh "$work/repo" --bundle-only >/dev/null || code=$?
listing="$(unzip -Z1 build/central-bundle.zip)"
check "a bundle alone needs no credentials" '[[ $code -eq 0 ]]'
check "the bundle holds the version's files" 'grep -qx "io/github/x/a/1.0.0/a-1.0.0.jar.asc" <<<"$listing"'
check "the bundle leaves maven-metadata out" '! grep -q maven-metadata <<<"$listing"'
code=0
env -u MAVEN_CENTRAL_USERNAME -u MAVEN_CENTRAL_PASSWORD scripts/central_upload.sh "$work/repo" >/dev/null 2>&1 || code=$?
check "an upload without credentials refused" '[[ $code -eq 2 ]]'
code="$(run PUBLISHED)"
bearer="Bearer $(printf 'user:token' | base64)"
check "published after validating" '[[ $code -eq 0 ]]'
check "the upload carries the bearer token" 'grep -q "^/api/v1/publisher/upload?name=appmonitor-.*&publishingType=AUTOMATIC $bearer [1-9]" "$work/requests-PUBLISHED.log"'
check "the status asked for that deployment" 'grep -q "^/api/v1/publisher/status?id=dep-1 $bearer" "$work/requests-PUBLISHED.log"'
code="$(run FAILED)"
check "a failed deployment fails" '[[ $code -eq 1 ]]'
check "its errors printed" 'grep -q "bad" "$work/out-FAILED.log"'
rm -rf "$work" build/central-bundle.zip
[[ $failures -eq 0 ]] || exit 1
