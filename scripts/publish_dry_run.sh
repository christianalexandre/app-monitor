#!/usr/bin/env bash
# SDK publication dry-run (ADR-0054, ADR-0058): the Kotlin SDKs to build/maven-dry-run
# with complete POMs, then a throwaway SwiftPM app resolves the root Package.swift by
# path and builds in debug and release (macOS with swift only); prints the files.
set -euo pipefail
cd "$(dirname "$0")/.."

repo=build/maven-dry-run
group_dir="$repo/io/github/christianalexandre"
rm -rf "$repo"
./gradlew -q publishAllPublicationsToDryRunRepository

fail=0
for base in appmonitor-protocol appmonitor-core appmonitor-okhttp appmonitor-no-op; do
  for suffix in "" -jvm -android; do
    artifact="$base$suffix"
    pom="$(find "$group_dir/$artifact" -name '*.pom' 2>/dev/null | head -1)"
    if [[ -z "$pom" ]]; then
      echo "publish: no POM for $artifact" >&2
      fail=1
      continue
    fi
    for tag in name description url license developer scm; do
      if ! grep -q "<$tag>" "$pom"; then
        echo "publish: $artifact POM has no <$tag>" >&2
        fail=1
      fi
    done
  done
done
[[ $fail -eq 0 ]] || exit 1
echo "publish: Maven files in $repo"
find "$repo" -type f \( -name '*.jar' -o -name '*.aar' -o -name '*.klib' -o -name '*.pom' -o -name '*.module' \) |
  sed "s|^$group_dir/||" | sort

if [[ "$(uname -s)" != "Darwin" ]] || ! command -v swift >/dev/null 2>&1; then
  echo "publish-swift: SKIPPED (needs macOS with swift on the PATH; ADR-0037)"
  exit 0
fi

# SwiftPM names a path dependency after its directory, lowercased.
root="$PWD"
identity="$(basename "$root" | tr '[:upper:]' '[:lower:]')"
consumer=build/spm-consumer
rm -rf "$consumer"
mkdir -p "$consumer/Sources/Consumer"
cat >"$consumer/Package.swift" <<EOF
// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "Consumer",
    platforms: [.macOS(.v12)],
    dependencies: [.package(path: "$root")],
    targets: [
        .executableTarget(name: "Consumer", dependencies: [.product(name: "AppMonitor", package: "$identity")])
    ]
)
EOF
cat >"$consumer/Sources/Consumer/main.swift" <<'EOF'
import AppMonitor

AppMonitor.start(AppMonitorOptions(enabled: false))
AppMonitor.stop()
EOF
for config in debug release; do
  swift build --package-path "$consumer" -c "$config" >"build/spm-consumer-$config.log" 2>&1 || {
    cat "build/spm-consumer-$config.log" >&2
    echo "publish-swift: consumer $config build failed" >&2
    exit 1
  }
  bin="$consumer/.build/$config/Consumer"
  echo "publish-swift: $config Consumer $(wc -c <"$bin" | tr -d ' ') bytes," \
    "$(nm "$bin" | grep -c SwiftProtobuf || true) SwiftProtobuf symbols (DEBT-0040)"
done
