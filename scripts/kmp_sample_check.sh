#!/usr/bin/env bash
# Builds samples/kmp (ADR-0065, ADR-0073): the Android app in debug and release, and the
# iOS host in Debug and Release for an iPhone simulator (SIM_DEVICE). Release holds no SDK
# but the no-op stubs, on both platforms (ADR-0062 D.1); local network keys in Debug only.
set -euo pipefail
cd "$(dirname "$0")/.."

tag=kmp-sample
mkdir -p build
log=build/kmp-sample.log
: >"$log"

if ! ./gradlew -q :samples:kmp:androidApp:assembleDebug :samples:kmp:androidApp:assembleRelease \
  :samples:kmp:androidApp:checkReleaseHasNoSdk >>"$log" 2>&1; then
  echo "$tag: Android build failed, full log in $log" >&2
  exit 1
fi

# $1 an APK: the SDK classes its dex files name.
apk_names() {
  unzip -p "$1" 'classes*.dex' | strings | grep -oE 'Lappmonitor/(sdk/[a-z]+|protocol|v1)/[A-Za-z0-9_]+' | sort -u
}
apks=samples/kmp/androidApp/build/outputs/apk
grep -qx 'Lappmonitor/sdk/core/MonitorClient' <<<"$(apk_names "$apks"/debug/*.apk)" \
  || { echo "$tag: no MonitorClient in the debug APK; the dex check sees nothing" >&2; exit 1; }
release_apk="$(apk_names "$apks"/release/*.apk | tr '\n' ' ')"
if [[ "$release_apk" != "Lappmonitor/sdk/core/AppMonitor Lappmonitor/sdk/core/AppMonitorOptions Lappmonitor/sdk/ktor/AppMonitorKtorKt " ]]; then
  echo "$tag: release APK holds SDK names other than the no-op stubs: $release_apk" >&2
  exit 1
fi

if [[ "$(uname -s)" != "Darwin" ]] || ! command -v xcodegen >/dev/null 2>&1; then
  echo "$tag: Android debug and release built; iOS SKIPPED (needs macOS with Xcode and 'brew install xcodegen')"
  exit 0
fi
source scripts/ios_lib.sh
device="$(ios_device)"

xcodegen generate --quiet --spec samples/kmp/iosApp/project.yml
products=build/kmp-sample/Build/Products
for config in Debug Release; do
  if ! xcodebuild build \
    -project samples/kmp/iosApp/KmpSample.xcodeproj -scheme KmpSample -configuration "$config" \
    -destination "platform=iOS Simulator,name=$device" \
    -derivedDataPath build/kmp-sample >>"$log" 2>&1; then
    grep -E 'error:' "$log" >&2 || true
    echo "$tag: iOS $config build failed, full log in $log" >&2
    exit 1
  fi
  check_network_keys "$config" "$products/$config-iphonesimulator/KmpSample.app"
done

# $1 a built app: the Swift SDK's names (nm) and Kotlin's type and package names, which
# Kotlin/Native keeps as UTF-16 strings in Debug and Release alike.
ios_names() {
  local binaries
  binaries="$(find "$1" -maxdepth 1 -type f \( -name KmpSample -o -name '*.dylib' \))"
  nm $binaries 2>/dev/null | awk '{print $NF}' | xcrun swift-demangle --compact |
    grep -oE '(^|[^A-Za-z_.])AppMonitor\.[A-Za-z0-9_]+' | sed -E 's/^[^A-Za-z]*AppMonitor\./swift:/'
  # shellcheck disable=SC2086
  python3 -I -c 'import re, sys
for path in sys.argv[1:]:
    for m in re.findall(rb"(?:[A-Za-z0-9_.]\x00){6,}", open(path, "rb").read()):
        print("kotlin:" + m.decode("utf-16-le"))' $binaries
}
app() { echo "$products/$1-iphonesimulator/KmpSample.app"; }
debug_names="$(ios_names "$(app Debug)" | sort -u)"
release_names="$(ios_names "$(app Release)" | sort -u)"
# The Kotlin names below are sentinels of the real SDK: the scan cannot tie a class to its package.
for name in swift:MonitorClient kotlin:MonitorClient kotlin:appmonitor.v1; do
  grep -qx "$name" <<<"$debug_names" || { echo "$tag: no $name in the Debug app; the symbol check sees nothing" >&2; exit 1; }
done
grep -qx kotlin:appmonitor.sample.kmp <<<"$release_names" \
  || { echo "$tag: no Kotlin name in the Release app; the symbol check sees nothing" >&2; exit 1; }
leaked="$(grep -E '^swift:|^kotlin:(appmonitor\.|Monitor(Client|Bridge|Sink))' <<<"$release_names" |
  grep -vxE 'swift:AppMonitor(Options)?|kotlin:appmonitor\.sdk\.(core|ktor)|kotlin:appmonitor\.sample\.kmp.*' | tr '\n' ' ' || true)"
if [[ -n "$leaked" ]]; then
  echo "$tag: Release app holds SDK names other than the no-op stubs: $leaked(ADR-0062 D.1)" >&2
  exit 1
fi
echo "$tag: Android and iOS ($device) debug and release built; keys and SDK in debug only"
