#!/usr/bin/env bash
# Tests scripts/tap_manifests.sh on stand-in packages: the cask's and the Scoop manifest's fields (ADR-0229).
set -euo pipefail
cd "$(dirname "$0")/.."
work="$PWD/build/test-tap-manifests"
rm -rf "$work"
mkdir -p "$work/pkgs"
printf 'dmg' >"$work/pkgs/app-monitor-1.2.3-macos-arm64.dmg"
printf 'zip' >"$work/pkgs/app-monitor-1.2.3-windows-x64.zip"
failures=0
check() { # name, condition
  if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

scripts/tap_manifests.sh 1.2.3 "$work/pkgs" "$work/out" >/dev/null
cask="$(cat "$work/out/Casks/app-monitor.rb")"
check "the cask has the version and the dmg's hash" 'grep -q "version \"1.2.3\"" <<<"$cask" && grep -q "sha256 \"$(sha "$work/pkgs/app-monitor-1.2.3-macos-arm64.dmg")\"" <<<"$cask"'
check "the cask downloads the release's dmg" 'grep -q "releases/download/#{version}/app-monitor-#{version}-macos-arm64.dmg" <<<"$cask"'
check "the cask is Apple silicon only and says how to open it" 'grep -q "depends_on arch: :arm64" <<<"$cask" && grep -q "Open Anyway" <<<"$cask"'
manifest="$work/out/bucket/app-monitor.json"
field() { python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); v=m
for k in sys.argv[2].split("."): v=v[int(k)] if isinstance(v,list) else v[k]
print(v)' "$manifest" "$1"; }
check "the manifest is JSON with the version" '[[ "$(field version)" == 1.2.3 ]]'
check "the manifest downloads the release's zip with its hash" '[[ "$(field architecture.64bit.url)" == */1.2.3/app-monitor-1.2.3-windows-x64.zip && "$(field architecture.64bit.hash)" == "$(sha "$work/pkgs/app-monitor-1.2.3-windows-x64.zip")" ]]'
check "the manifest opens the app from its folder" '[[ "$(field extract_dir)" == "App Monitor" && "$(field shortcuts.0.0)" == "App Monitor.exe" ]]'
check "the manifest updates itself from the next release" '[[ "$(field autoupdate.architecture.64bit.url)" == *'"'"'/$version/app-monitor-$version-windows-x64.zip'"'"' ]]'
check "the README gives both install lines" 'grep -q "brew install --cask christianalexandre/tap/app-monitor" "$work/out/README.md" && grep -q "scoop install christianalexandre/app-monitor" "$work/out/README.md"'
code=0
scripts/tap_manifests.sh 1.2.3-SNAPSHOT "$work/pkgs" "$work/x" >/dev/null 2>&1 || code=$?
check "a snapshot refused" '[[ $code -eq 2 ]]'
code=0
scripts/tap_manifests.sh 9.9.9 "$work/pkgs" "$work/y" >/dev/null 2>&1 || code=$?
check "a version without its packages refused" '[[ $code -eq 1 ]]'
rm -rf "$work"
[[ $failures -eq 0 ]] || exit 1
